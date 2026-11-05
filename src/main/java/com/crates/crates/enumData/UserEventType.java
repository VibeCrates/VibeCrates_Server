package com.crates.crates.enumData;

/**
 * 사용자 행동 로그의 종류(docs/board-schema.md 13장).
 *
 * <ul>
 *   <li>BOARD_LIKE — 보드 좋아요. 강한 긍정</li>
 *   <li>BOARD_UNLIKE — 좋아요 취소. 약한 부정. 좋아요한 내 보드를 지워 좋아요가 함께 사라진 경우도 포함한다</li>
 *   <li>BOARD_DELETE — 내 보드 삭제</li>
 *   <li>BOARD_EDIT — 저장한 보드 수정. 뺀 작품은 확실한 부정, 넣은 작품은 확실한 정답</li>
 *   <li>BOARD_CAPTURE — 보드 사진 저장. 강한 긍정</li>
 * </ul>
 */
public enum UserEventType {
    BOARD_LIKE, BOARD_UNLIKE, BOARD_DELETE, BOARD_EDIT, BOARD_CAPTURE
}
