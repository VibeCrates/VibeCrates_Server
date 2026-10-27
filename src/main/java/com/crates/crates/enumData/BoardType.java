package com.crates.crates.enumData;

/**
 * 보드가 만들어진 경위. 규칙은 docs/board-schema.md 2장, 11장.
 *
 * <ul>
 *   <li>USER_CUSTOM — 사용자가 만든 보드. 소유자가 있다.</li>
 *   <li>AI_RECOMMEND — 홈 추천 보드. 좋아요를 누르는 순간 저장되는 전역 공용 보드. 구성이 같으면 같은 보드다.</li>
 *   <li>SEARCH_RECOMMEND — 검색어로 추천된 보드. 좋아요를 누르는 순간 저장되는 전역 공용 보드.
 *       제목이 검색어이고, 제목과 구성이 모두 같아야 같은 보드다.</li>
 * </ul>
 */
public enum BoardType {
    USER_CUSTOM, AI_RECOMMEND, SEARCH_RECOMMEND
}
