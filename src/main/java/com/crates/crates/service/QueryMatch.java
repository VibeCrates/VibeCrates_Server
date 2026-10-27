package com.crates.crates.service;

/**
 * 검색된 query 하나. vector는 Qdrant가 Cosine 컬렉션에 넣을 때 정규화한 값이라 크기가 1이다
 * (후보끼리 내적이 곧 코사인 유사도다, BoardTitleService.pickDistinct).
 */
public record QueryMatch(Long queryId, String queryText, float score, float[] vector) {
}
