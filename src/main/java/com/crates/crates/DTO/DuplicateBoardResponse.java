package com.crates.crates.DTO;

/** 중복으로 거절된 보드 저장 응답의 data. boardId는 이미 있는 내 보드다. */
public record DuplicateBoardResponse(Long boardId) {
}
