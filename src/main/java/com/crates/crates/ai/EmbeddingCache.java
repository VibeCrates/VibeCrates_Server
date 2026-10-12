package com.crates.crates.ai;

import com.crates.crates.enumData.SearchDomain;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * 검색어 → 임베딩 벡터와 domain을 Redis에 둔다. 같은 검색어는 AI 서버를 다시 부르지 않는다.
 *
 * <h2>왜</h2>
 *
 * <p>검색 보드의 처리량은 AI 서버가 정한다(한글 단건 동시 요청 기준 초당 약 7건). 같은 문장의 벡터는 같은 모델이면
 * 늘 같으므로(묶음·단건 API의 코사인 유사도 1.000000), 한 번 바꾼 벡터를 두면 다시 검색할 때 AI 서버를 부르지 않는다.
 * 검색어는 겹치는 일이 많지 않아, 짧은 시간 안의 반복(같은 사용자의 재검색, 같은 화제로 몰리는 검색)만 노린다.
 * 키 수 ≈ TTL 동안의 서로 다른 검색어 수라 TTL을 짧게 둔다({@code ai.embedding.cache.ttl}).</p>
 *
 * <h2>값 형식</h2>
 *
 * <p>{@code model_version \n domain \n 벡터(float32 리틀 엔디언 Base64)}. domain을 담기 전 형식(줄 2개)의 값은
 * 없는 것으로 보고 AI 서버를 다시 불러 덮어쓴다.</p>
 *
 * <h2>낡은 벡터</h2>
 *
 * <p>값에 벡터를 만든 model_version을 함께 담는다. AI 서버가 최근 응답에 실어 온 버전과 다르면 없는 것으로 본다.
 * 기동 직후 아직 AI 응답을 받지 못했을 때는 담긴 값을 그대로 쓴다. 모델이 바뀌면 콘텐츠 벡터도 다시 적재해야 하므로
 * 그 사이의 짧은 어긋남은 받아들인다. TTL은 버전 비교가 놓친 경우를 위한 안전망이다.</p>
 *
 * <p>Redis가 응답하지 않으면 오류를 올리지 않고 없는 것으로 본다. 검색은 AI 서버를 불러 그대로 나간다.</p>
 */
@Slf4j
@Component
public class EmbeddingCache {

    private static final String KEY_PREFIX = "emb:v1:";

    private final StringRedisTemplate redis;
    private final AiEmbeddingClient client;
    private final boolean enabled;
    private final Duration ttl;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    public EmbeddingCache(StringRedisTemplate redis,
                          AiEmbeddingClient client,
                          MeterRegistry registry,
                          @Value("${ai.embedding.cache.enabled}") boolean enabled,
                          @Value("${ai.embedding.cache.ttl}") Duration ttl)
    {
        this.redis = redis;
        this.client = client;
        this.enabled = enabled;
        this.ttl = ttl;
        this.hits = counter(registry, "hit");
        this.misses = counter(registry, "miss");
        this.errors = counter(registry, "error");
    }

    private static Counter counter(MeterRegistry registry, String result)
    {
        return Counter.builder("crates.ai.embedding.cache")
                .description("검색어 임베딩 캐시 조회 결과. hit 비율이 곧 AI 서버 호출을 건너뛴 비율이다.")
                .tag("result", result)
                .register(registry);
    }

    public Optional<QueryEmbedding> get(String query)
    {
        if (!enabled)
        {
            return Optional.empty();
        }
        try
        {
            String value = redis.opsForValue().get(KEY_PREFIX + query);
            if (value != null)
            {
                String[] parts = value.split("\n", 3);
                String current = client.lastModelVersion();
                Optional<SearchDomain> domain = parts.length == 3 ? SearchDomain.fromAiValue(parts[1]) : Optional.empty();
                if (domain.isPresent() && (current == null || current.equals(parts[0])))
                {
                    hits.increment();
                    return Optional.of(new QueryEmbedding(decode(parts[2]), domain.get()));
                }
            }
            misses.increment();
            return Optional.empty();
        }
        catch (Exception e)
        {
            errors.increment();
            log.warn("검색어 임베딩 캐시를 읽지 못해 AI 서버를 부릅니다. query: {}", query, e);
            return Optional.empty();
        }
    }

    public void put(String query, QueryEmbedding embedding)
    {
        String modelVersion = client.lastModelVersion();
        if (!enabled || modelVersion == null)
        {
            return;
        }
        try
        {
            String value = modelVersion + '\n' + embedding.domain().name() + '\n' + encode(embedding.vector());
            redis.opsForValue().set(KEY_PREFIX + query, value, ttl);
        }
        catch (RuntimeException e)
        {
            errors.increment();
            log.warn("검색어 임베딩 캐시를 저장하지 못했습니다. 다음 검색이 AI 서버를 다시 부릅니다. query: {}", query, e);
        }
    }

    /** float32 리틀 엔디언을 Base64로. 768차원이면 약 4KB다. */
    private static String encode(float[] vector)
    {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(vector);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private static float[] decode(String base64)
    {
        ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(base64)).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[buffer.remaining() / Float.BYTES];
        buffer.asFloatBuffer().get(vector);
        return vector;
    }
}
