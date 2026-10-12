package com.crates.crates.ai;

/**
 * 텍스트를 임베딩 벡터로 변환한다.
 *
 * <p>구현체는 {@link BatchingEmbeddingClient} 하나다(캐시 → 묶음 호출). 인터페이스로 남겨둔 이유는
 * 테스트에서 AI 서버 없이 대역을 끼우기 위해서다.</p>
 */
public interface EmbeddingClient {

    /**
     * @return 길이가 {@code ai.server.embedding-dimension}인 float32 벡터
     */
    float[] embed(String text);
}
