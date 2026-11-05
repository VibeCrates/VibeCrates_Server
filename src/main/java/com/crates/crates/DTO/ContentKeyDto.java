package com.crates.crates.DTO;

/**
 * 콘텐츠를 가리키는 값들. 사용자 행동 로그의 아이템으로 쓴다(docs/board-schema.md 13-2).
 *
 * @param id        content.id. 백엔드가 쓴다. DB를 다시 적재하면 바뀐다
 * @param type      콘텐츠 종류(dtype: BOOK / MOVIE / MUSIC)
 * @param sourceKey 원본 데이터셋의 id. AI 서버와 벡터 CSV가 콘텐츠를 가리키는 값이다
 */
public record ContentKeyDto(Long id, String type, String sourceKey) {
}
