package com.crates.crates.DTO;

import java.util.List;

/**
 * @param liked   조회한 사용자가 이 보드에 좋아요를 눌렀는지.
 *                likeCount는 board 테이블의 집계값이고, liked는 board_feedback에서 온
 *                사용자별 정보라 출처가 다르다.
 * @param deleted 주인이 지운 보드인지. 좋아요한 사람의 보관함에만 이런 보드가 보인다.
 *                새 좋아요를 받지 않으므로, 프론트는 취소하기 전에 "다시 좋아요할 수 없다"고 알려 줄 수 있다.
 * @param owned   조회한 사용자가 만든 보드인지. 프론트는 이 값으로 삭제 버튼을 보여 준다(주인만 지울 수 있다).
 */
public record BoardWithContentsDto(
        Long boardId,
        String title,
        Long likeCount,
        boolean liked,
        boolean deleted,
        boolean owned,
        List<ContentResponseDto> contents
) {

    /**
     * 아직 DB에 저장되지 않은 홈 추천·검색 보드. 응답에는 좋아요 토큰을 붙여 내보낸다
     * ({@link RecommendedBoardDto}, {@link SearchBoardDto}). 좋아요는 boardId가 아니라 그 토큰으로 보낸다.
     */
    public static BoardWithContentsDto unsaved(String title, List<ContentResponseDto> contents)
    {
        return new BoardWithContentsDto(null, title, 0L, false, false, false, contents);
    }
}
