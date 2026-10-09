package com.crates.crates.service;

/**
 * @param queryText    보드 제목(한국어). 화면에 그대로 보여준다
 * @param queryEn      영어 원문. 벡터는 이 문장으로 만들었다
 * @param modelVersion 벡터를 만든 AI 모델 체크포인트. 쓸 때만 필요하다.
 */
public record QueryVectorRecord(Long queryId, String queryText, String queryEn, float[] vector, String modelVersion) {
}
