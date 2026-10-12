package com.crates.crates.ai;

import java.util.List;

/** AI 서버 묶음 임베딩 요청. {@code POST /api/v1/embeddings} {"query": ["...", ...]} */
public record EmbeddingRequest(List<String> query) {
}
