package com.crates.crates.DTO;

import java.util.List;

/**
 * 검색 보드 응답. 모양은 {@link BoardWithContentsDto}와 같고 좋아요 토큰이 하나 더 붙는다.
 *
 * <p>검색 보드에 좋아요할 때는 boardId가 있어도 항상 {@code POST /api/search/board/likes}로 이 토큰을 보낸다.
 * 서버는 토큰 안의 검색어와 콘텐츠로 "있으면 좋아요, 없으면 저장 후 좋아요"를 처리하므로, 그 사이 보드가
 * 지워졌어도 실패하지 않는다. 취소는 다른 보드처럼 boardId로 한다(docs/board-schema.md 11-8).</p>
 *
 * @param boardId   같은 제목·구성의 검색 보드가 이미 저장돼 있으면 그 id, 아니면 null
 * @param title     검색어(NFC 변환과 앞뒤 공백 제거만 한 값)
 * @param likeToken 이 검색어로 이 콘텐츠를 보여 줬다는 서버 서명(SearchLikeTokenService)
 */
public record SearchBoardDto(
        Long boardId,
        String title,
        Long likeCount,
        boolean liked,
        List<ContentResponseDto> contents,
        String likeToken
) {
}
