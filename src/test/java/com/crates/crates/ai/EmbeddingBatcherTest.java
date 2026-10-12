package com.crates.crates.ai;

import com.crates.crates.Global.exception.AiServerBusyException;
import com.crates.crates.Global.exception.AiServerException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingBatcherTest {

    private final AiEmbeddingClient client = mock(AiEmbeddingClient.class);
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    private EmbeddingBatcher batcher;

    private EmbeddingBatcher start(int maxConcurrentCalls, int maxPending, Duration maxWait)
    {
        batcher = new EmbeddingBatcher(client, new SimpleMeterRegistry(), 50, Duration.ofMillis(50),
                maxConcurrentCalls, maxPending, maxWait);
        batcher.start();
        return batcher;
    }

    @AfterEach
    void tearDown()
    {
        requests.shutdownNow();
        if (batcher != null) batcher.shutdown();
    }

    /** 검색어 첫 글자 코드를 값으로 하는 1차원 벡터. 어느 요청에 어느 벡터가 갔는지 확인한다. */
    private static float[] vectorOf(String text)
    {
        return new float[] {text.charAt(0)};
    }

    @Test
    void window_안에_온_요청은_한_번에_보내고_같은_검색어는_한_번만_보낸다() throws Exception
    {
        List<List<String>> calls = new CopyOnWriteArrayList<>();
        when(client.embedAll(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            calls.add(texts);
            return texts.stream().map(EmbeddingBatcherTest::vectorOf).toList();
        });
        start(2, 60, Duration.ofSeconds(5));

        List<String> texts = List.of("재즈", "록", "재즈", "공포");
        List<CompletableFuture<float[]>> results = new ArrayList<>();
        for (String text : texts)
        {
            results.add(CompletableFuture.supplyAsync(() -> batcher.embed(text), requests));
        }

        for (int i = 0; i < texts.size(); i++)
        {
            assertThat(results.get(i).get(2, TimeUnit.SECONDS)).containsExactly(vectorOf(texts.get(i)));
        }
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst()).containsExactlyInAnyOrder("재즈", "록", "공포");
    }

    @Test
    void 받아_둘_자리가_없으면_바로_503() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        when(client.embedAll(anyList())).thenAnswer(invocation -> {
            release.await();
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(EmbeddingBatcherTest::vectorOf).toList();
        });
        start(1, 2, Duration.ofSeconds(5));

        CompletableFuture<float[]> first = CompletableFuture.supplyAsync(() -> batcher.embed("a"), requests);
        CompletableFuture<float[]> second = CompletableFuture.supplyAsync(() -> batcher.embed("b"), requests);
        Thread.sleep(200);

        assertThatThrownBy(() -> batcher.embed("c")).isInstanceOf(AiServerBusyException.class);

        release.countDown();
        assertThat(first.get(2, TimeUnit.SECONDS)).containsExactly(vectorOf("a"));
        assertThat(second.get(2, TimeUnit.SECONDS)).containsExactly(vectorOf("b"));
        // 자리가 돌아오면 다시 받는다
        assertThat(batcher.embed("d")).containsExactly(vectorOf("d"));
    }

    @Test
    void max_wait_안에_끝나지_않으면_503() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        when(client.embedAll(anyList())).thenAnswer(invocation -> {
            release.await();
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(EmbeddingBatcherTest::vectorOf).toList();
        });
        start(1, 60, Duration.ofMillis(300));

        assertThatThrownBy(() -> batcher.embed("a")).isInstanceOf(AiServerBusyException.class);
        release.countDown();
    }

    @Test
    void AI_호출이_실패하면_묶음의_모든_요청이_AiServerException() throws Exception
    {
        when(client.embedAll(anyList())).thenThrow(new AiServerException("AI 서버 통신에 실패했습니다."));
        start(2, 60, Duration.ofSeconds(5));

        CompletableFuture<float[]> first = CompletableFuture.supplyAsync(() -> batcher.embed("a"), requests);
        CompletableFuture<float[]> second = CompletableFuture.supplyAsync(() -> batcher.embed("b"), requests);

        for (CompletableFuture<float[]> result : List.of(first, second))
        {
            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AiServerException.class)
                    .cause().isNotInstanceOf(AiServerBusyException.class);
        }
    }
}
