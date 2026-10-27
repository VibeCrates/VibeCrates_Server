package com.crates.crates.DTO;

import java.util.List;

/**
 * 홈 추천 보드 응답. 모양은 {@link SearchBoardDto}와 같다 — 저장 전 보드라 좋아요 토큰이 붙는다.
 *
 * <p>홈 추천 보드에 좋아요할 때는 boardId가 있어도 항상 {@code POST /api/boards/likes}로 이 토큰을 보낸다.
 * 서버는 토큰 안의 제목과 콘텐츠로 "있으면 좋아요, 없으면 저장 후 좋아요"를 처리하므로, 그 사이 보드가
 * 지워졌어도 실패하지 않는다. 취소는 다른 보드처럼 boardId로 한다(docs/board-schema.md 11-11).</p>
 *
 * @param boardId   같은 구성의 AI 보드가 이미 저장돼 있으면 그 id, 아니면 null
 * @param title     저장된 보드면 저장 시점의 제목, 아니면 이번에 고른 제목(query)
 * @param likeToken 이 제목으로 이 콘텐츠를 보여 줬다는 서버 서명(BoardLikeTokenService)
 */
public record RecommendedBoardDto(
        Long boardId,
        String title,
        Long likeCount,
        boolean liked,
        List<ContentResponseDto> contents,
        String likeToken
) {
}
