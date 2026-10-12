package com.crates.crates.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 검색어를 임베딩 캐시에서 먼저 찾고, 없으면 {@link EmbeddingBatcher}로 AI 서버에 묶어 보낸다.
 *
 * <p>검색어는 앞뒤 공백을 지우고 연속 공백을 하나로 줄인 뒤 캐시 키와 AI 입력으로 쓴다. 대소문자는 그대로 둔다 —
 * "SF"와 "sf"는 AI 서버가 다르게 번역·임베딩할 수 있다.</p>
 */
@Component
@RequiredArgsConstructor
public class BatchingEmbeddingClient implements EmbeddingClient {

    private final EmbeddingCache cache;
    private final EmbeddingBatcher batcher;

    @Override
    public QueryEmbedding embed(String text)
    {
        String query = text.strip().replaceAll("\\s+", " ");
        return cache.get(query).orElseGet(() -> {
            QueryEmbedding embedding = batcher.embed(query);
            cache.put(query, embedding);
            return embedding;
        });
    }
}
