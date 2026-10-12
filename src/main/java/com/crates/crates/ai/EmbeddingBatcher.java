package com.crates.crates.ai;

import com.crates.crates.Global.exception.AiServerBusyException;
import com.crates.crates.Global.exception.AiServerException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 검색어 임베딩 요청을 잠깐 모아 AI 서버의 묶음 API로 보내고, AI 서버로 가는 요청 수에 상한을 둔다.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>AI 서버는 한글 검색어를 영어로 번역한 뒤 임베딩해서, 단건 요청을 동시에 보내면 초당 약 7건밖에 처리하지 못한다.
 * 1초에 200건을 보내면 마지막 응답이 약 25초 뒤에 왔고, 14%는 연결조차 받지 못하고 끊겼다(2026-10-10 측정).
 * 같은 검색어를 묶음 API로 10~50건씩 보내면 초당 21~28건을 처리한다. 그리고 백엔드는 응답을 기다리는 동안
 * 톰캣 스레드를 붙잡으므로, 기다리는 요청이 끝없이 늘면 AI와 상관없는 API까지 멈춘다.</p>
 *
 * <h2>어떻게</h2>
 *
 * <ul>
 *   <li><b>시간 기준으로 묶는다.</b> 첫 요청이 오면 {@code window}만큼만 더 기다려 그사이 온 것을 함께 보낸다
 *       (최대 {@code max-size}건). 정해진 인원을 기다리지 않으므로 한가할 때는 요청 하나가 {@code window} 뒤 혼자 나간다.
 *       AI 호출 자리가 모두 차 있으면 그동안 쌓인 요청이 다음 묶음이 되어, 붐빌수록 저절로 크게 묶인다.</li>
 *   <li><b>AI 서버로 동시에 보내는 묶음 수에 상한을 둔다</b>({@code max-concurrent-calls}). AI 서버가 연결을 끊지 않을 만큼만 보낸다.</li>
 *   <li><b>받아 둘 요청 수에 상한을 둔다</b>({@code max-pending}, 대기 + 처리 중). 넘치면 바로 503으로 돌려보낸다.
 *       검색 보드가 붙잡을 수 있는 톰캣 스레드가 이 수를 넘지 않아, 나머지 API가 쓸 스레드가 늘 남는다.</li>
 *   <li><b>한 요청이 기다리는 시간에 상한을 둔다</b>({@code max-wait}). 넘기면 503이다. 그보다 오래 걸리면 사용자는 이미 떠났다.</li>
 * </ul>
 *
 * <p>같은 묶음 안의 같은 검색어는 한 번만 보낸다.</p>
 */
@Slf4j
@Component
public class EmbeddingBatcher {

    private static final String BUSY_MESSAGE = "검색 요청이 많아 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";

    /** 받아 둔 요청 하나. AI 응답이 오면 {@code result}가 끝난다. */
    private record Pending(String text, CompletableFuture<float[]> result) {
    }

    private final AiEmbeddingClient client;
    private final int maxBatchSize;
    private final Duration window;
    private final Duration maxWait;

    private final BlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    /** 받아 둔 요청(대기 + 처리 중) 자리. 요청이 끝나면(성공·실패·시간 초과) 돌려준다. */
    private final Semaphore pendingSlots;
    /** AI 서버로 동시에 보내는 묶음 자리. */
    private final Semaphore callSlots;
    /** AI 호출은 응답을 기다리는 시간이 대부분이라 가상 스레드에서 돈다. 개수는 callSlots가 정한다. */
    private final ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
    private Thread dispatcher;

    private final DistributionSummary batchSize;
    private final Timer callTime;
    private final Counter rejectedFull;
    private final Counter rejectedTimeout;

    public EmbeddingBatcher(AiEmbeddingClient client,
                            MeterRegistry registry,
                            @Value("${ai.embedding.batch.max-size}") int maxBatchSize,
                            @Value("${ai.embedding.batch.window}") Duration window,
                            @Value("${ai.embedding.batch.max-concurrent-calls}") int maxConcurrentCalls,
                            @Value("${ai.embedding.batch.max-pending}") int maxPending,
                            @Value("${ai.embedding.batch.max-wait}") Duration maxWait)
    {
        if (maxBatchSize <= 0 || maxConcurrentCalls <= 0 || maxPending <= 0)
        {
            throw new IllegalStateException("ai.embedding.batch의 max-size, max-concurrent-calls, max-pending은 1 이상이어야 합니다.");
        }
        this.client = client;
        this.maxBatchSize = maxBatchSize;
        this.window = window;
        this.maxWait = maxWait;
        this.pendingSlots = new Semaphore(maxPending);
        this.callSlots = new Semaphore(maxConcurrentCalls);

        Gauge.builder("crates.ai.embedding.queue", queue, BlockingQueue::size)
                .description("AI 서버로 보내기를 기다리는 검색어 수. 계속 차 있으면 AI 처리량이 도착률을 못 따라간다는 뜻이다.")
                .register(registry);
        this.batchSize = DistributionSummary.builder("crates.ai.embedding.batch.size")
                .description("AI 서버에 한 번에 보낸 검색어 수")
                .register(registry);
        this.callTime = Timer.builder("crates.ai.embedding.call")
                .description("묶음 임베딩 호출 하나의 응답 시간")
                .register(registry);
        this.rejectedFull = rejected(registry, "full");
        this.rejectedTimeout = rejected(registry, "timeout");
    }

    private static Counter rejected(MeterRegistry registry, String reason)
    {
        return Counter.builder("crates.ai.embedding.rejected")
                .description("503으로 돌려보낸 검색어. full = 받아 둘 자리가 없음, timeout = max-wait 안에 차례가 오지 않음")
                .tag("reason", reason)
                .register(registry);
    }

    @PostConstruct
    void start()
    {
        dispatcher = Thread.ofPlatform().daemon().name("ai-embedding-dispatcher").start(this::dispatchLoop);
    }

    @PreDestroy
    void shutdown()
    {
        dispatcher.interrupt();
        callers.shutdownNow();
    }

    /**
     * 검색어 하나를 벡터로 바꾼다. 차례가 와서 AI 응답을 받을 때까지 호출한 스레드가 기다린다.
     *
     * @throws AiServerBusyException 받아 둘 자리가 없거나 {@code max-wait} 안에 끝나지 않음(503)
     * @throws AiServerException     AI 서버 호출 실패(502)
     */
    public float[] embed(String text)
    {
        if (!pendingSlots.tryAcquire())
        {
            rejectedFull.increment();
            throw new AiServerBusyException(BUSY_MESSAGE);
        }
        Pending pending = new Pending(text, new CompletableFuture<>());
        pending.result().whenComplete((vector, error) -> pendingSlots.release());
        queue.add(pending);

        try
        {
            return pending.result().get(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e)
        {
            // 아직 보내지 않았다면 묶음에서 빠진다(nextBatch가 끝난 요청을 건너뛴다).
            pending.result().cancel(false);
            rejectedTimeout.increment();
            throw new AiServerBusyException(BUSY_MESSAGE);
        }
        catch (ExecutionException e)
        {
            if (e.getCause() instanceof AiServerException aiServerException)
            {
                throw aiServerException;
            }
            throw new AiServerException("AI 서버 통신에 실패했습니다.", e.getCause());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            pending.result().cancel(false);
            throw new AiServerException("검색어 임베딩을 기다리다 중단됐습니다.", e);
        }
    }

    private void dispatchLoop()
    {
        while (!Thread.currentThread().isInterrupted())
        {
            try
            {
                // 호출 자리를 먼저 잡는다. 자리가 없는 동안 들어온 요청은 큐에 쌓여 다음 묶음이 된다.
                callSlots.acquire();
                List<Pending> batch = nextBatch();
                if (batch.isEmpty())
                {
                    callSlots.release();
                    continue;
                }
                callers.execute(() -> {
                    try
                    {
                        call(batch);
                    }
                    finally
                    {
                        callSlots.release();
                    }
                });
            }
            catch (InterruptedException e)
            {
                return;
            }
            catch (RuntimeException e)
            {
                // 디스패처가 죽으면 이후 검색이 모두 max-wait 뒤 503이 된다. 남기고 계속 돈다.
                callSlots.release();
                log.error("검색어 임베딩 묶음을 보내지 못했습니다.", e);
            }
        }
    }

    /** 첫 요청을 기다린 뒤 window 동안 더 모은다. 이미 쌓여 있으면 기다리지 않고 바로 꺼낸다. 시간이 지나 끝난 요청은 뺀다. */
    private List<Pending> nextBatch() throws InterruptedException
    {
        List<Pending> batch = new ArrayList<>(maxBatchSize);
        addIfWaiting(batch, queue.take());

        long deadline = System.nanoTime() + window.toNanos();
        while (batch.size() < maxBatchSize)
        {
            Pending next = queue.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (next == null)
            {
                break;
            }
            addIfWaiting(batch, next);
        }
        return batch;
    }

    private static void addIfWaiting(List<Pending> batch, Pending pending)
    {
        if (!pending.result().isDone())
        {
            batch.add(pending);
        }
    }

    private void call(List<Pending> batch)
    {
        Map<String, List<Pending>> byText = new LinkedHashMap<>();
        for (Pending pending : batch)
        {
            byText.computeIfAbsent(pending.text(), text -> new ArrayList<>()).add(pending);
        }
        List<String> texts = List.copyOf(byText.keySet());
        batchSize.record(texts.size());

        try
        {
            List<float[]> vectors = callTime.recordCallable(() -> client.embedAll(texts));
            for (int i = 0; i < texts.size(); i++)
            {
                float[] vector = vectors.get(i);
                byText.get(texts.get(i)).forEach(pending -> pending.result().complete(vector));
            }
        }
        catch (Exception e)
        {
            AiServerException error = e instanceof AiServerException aiServerException
                    ? aiServerException
                    : new AiServerException("AI 서버 통신에 실패했습니다.", e);
            batch.forEach(pending -> pending.result().completeExceptionally(error));
        }
    }
}
