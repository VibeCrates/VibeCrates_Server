package com.crates.crates.enumData;

/**
 * 보관함 탭. "내 보드"를 어떤 기준으로 추릴지 정한다.
 *
 * <p>세 값이 서로 배타적이지 않다는 점이 중요하다. 좋아요한 보드를 고치면 새로 저장된 보드가 내
 * USER_CUSTOM 보드가 되고, 내가 만든 보드에 내가 좋아요를 누를 수도 있다. 즉 LIKED와
 * CREATED에 동시에 속하는 보드가 정상적으로 존재하며, 양쪽 목록에 모두 나오는 것이 맞다.
 * 각 목록은 "이 조건을 만족하는 보드 전부"라는 뜻이지 서로 나눠 가진 몫이 아니다.</p>
 */
public enum MyBoardFilter {

    /** 내가 만든 보드 + 내가 좋아요한 보드의 합집합. 홈의 "내 보드" 섹션이 보는 것과 같다. */
    ALL,

    /** 내가 좋아요한 보드. board_feedback에서 가져온다. */
    LIKED,

    /** 내가 만든 보드. board.board_type = USER_CUSTOM이고 소유자가 나인 것. */
    CREATED
}
