package com.crates.crates.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * AI 서버 묶음 임베딩 응답. {@code vectors}는 요청한 순서 그대로다.
 *
 * @param modelVersion 벡터를 만든 체크포인트. 콘텐츠 벡터(Qdrant payload model_version)와 같아야 검색이 의미가 있다
 */
public record EmbeddingResponse(int dim,
                                List<float[]> vectors,
                                @JsonProperty("model_version") String modelVersion) {
}
