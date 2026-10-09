package com.crates.crates.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 사용자별 홈 추천 결과 중 Qdrant에서 얻은 부분을 Redis에 둔다.
 *
 * <h2>왜</h2>
 *
 * <p>추천 1건은 Qdrant 검색을 최대 5번 한다(콘텐츠 1번 + 보드 제목 묶음 검색). 부하 측정에서 최대 처리량을 정한 것은
 * 언제나 Qdrant CPU였다. 추천은 취향 벡터가 바뀔 때만 달라지고, 취향 벡터는 좋아요 뒤 재계산 때만 바뀐다 —
 * 그 사이에 홈에 다시 들어오면 같은 계산을 반복한다. 브라우저 캐시(React Query, 30초)는 새로고침·재접속에는 소용이 없다.</p>
 *
 * <h2>무엇을 담나</h2>
 *
 * <p>Qdrant에서 얻은 것만 담는다: 추천 콘텐츠 id(순서 그대로), 보드별 제목(query), 계산에 쓴 취향 벡터의 버전.
 * 보드 저장 여부·좋아요 수·내 좋아요 여부·콘텐츠 요약은 담지 않는다 — 계속 바뀌는 값이라 매번 PostgreSQL에서 읽는다.
 * 그래서 좋아요 상태는 늘 최신이다.</p>
 *
 * <h2>언제 버리나</h2>
 *
 * <p>지우는 코드를 두지 않고 버전으로 가른다. 꺼낼 때 지금 취향 벡터의 {@code updated_at}과 담을 때의 값이 다르면
 * 없는 것으로 본다. 지우기를 빠뜨려 낡은 추천이 나갈 길이 없다. TTL은 버전 비교가 놓친 경우를 위한 안전망이다.</p>
 *
 * <p>Redis가 응답하지 않으면 오류를 올리지 않고 없는 것으로 본다. 추천은 지금처럼 Qdrant에서 계산해 나간다.</p>
 */
@Slf4j
@Component
public class RecommendationCache {

    private static final String KEY_PREFIX = "reco:v1:";
    private static final Duration TTL = Duration.ofHours(24);

    /** 담는 값. 제목은 보드 순서(콘텐츠 8건씩 끊은 순서)와 같다. */
    public record Entry(String vectorVersion, List<Long> contentIds, List<String> titles) {
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    public RecommendationCache(StringRedisTemplate redis, ObjectMapper objectMapper, MeterRegistry registry)
    {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.hits = counter(registry, "hit");
        this.misses = counter(registry, "miss");
        this.errors = counter(registry, "error");
    }

    private static Counter counter(MeterRegistry registry, String result)
    {
        return Counter.builder("crates.recommendation.cache")
                .description("홈 추천 서버 캐시 조회 결과. hit 비율이 곧 Qdrant 검색을 건너뛴 비율이다.")
                .tag("result", result)
                .register(registry);
    }

    /** 이 버전의 취향 벡터로 계산해 둔 결과. 없거나 버전이 다르면 빈 값. */
    public Optional<Entry> get(Long userId, String vectorVersion)
    {
        try
        {
            String json = redis.opsForValue().get(KEY_PREFIX + userId);
            if (json != null)
            {
                Entry entry = objectMapper.readValue(json, Entry.class);
                if (vectorVersion.equals(entry.vectorVersion()))
                {
                    hits.increment();
                    return Optional.of(entry);
                }
            }
            misses.increment();
            return Optional.empty();
        }
        catch (Exception e)
        {
            errors.increment();
            log.warn("추천 캐시를 읽지 못해 다시 계산합니다. userId: {}", userId, e);
            return Optional.empty();
        }
    }

    public void put(Long userId, Entry entry)
    {
        try
        {
            redis.opsForValue().set(KEY_PREFIX + userId, objectMapper.writeValueAsString(entry), TTL);
        }
        catch (JsonProcessingException | RuntimeException e)
        {
            errors.increment();
            log.warn("추천 캐시를 저장하지 못했습니다. 다음 요청이 다시 계산합니다. userId: {}", userId, e);
        }
    }
}
