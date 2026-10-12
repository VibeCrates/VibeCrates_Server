package com.crates.crates.ai;

import com.crates.crates.enumData.SearchDomain;

/**
 * 검색어 하나를 AI 서버로 바꾼 결과.
 *
 * @param vector 검색어 임베딩 벡터(길이 {@code ai.server.embedding-dimension})
 * @param domain 검색어가 가리키는 콘텐츠 종류. 검색 보드에 어떤 종류를 담을지 정한다
 */
public record QueryEmbedding(float[] vector, SearchDomain domain) {
}
