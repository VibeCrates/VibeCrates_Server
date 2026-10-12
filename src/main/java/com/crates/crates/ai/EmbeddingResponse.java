package com.crates.crates.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * AI 서버 묶음 임베딩 응답. {@code vectors}와 {@code domains}는 요청한 순서 그대로다.
 *
 * @param domains      검색어마다 가리키는 콘텐츠 종류(all, book, movie, music). 이 필드가 생기기 전의 AI 서버는 보내지 않는다
 * @param modelVersion 벡터를 만든 체크포인트. 콘텐츠 벡터(Qdrant payload model_version)와 같아야 검색이 의미가 있다
 */
public record EmbeddingResponse(int dim,
                                List<float[]> vectors,
                                List<String> domains,
                                @JsonProperty("model_version") String modelVersion) {
}
