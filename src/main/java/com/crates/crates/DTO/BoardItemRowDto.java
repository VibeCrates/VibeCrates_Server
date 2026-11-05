package com.crates.crates.DTO;

import com.crates.crates.entity.board.ItemLayout;

/**
 * 보드 목록 응답을 만들 때 쓰는 board_item 한 줄. 콘텐츠 id와 배치를 한 번에 읽는다.
 *
 * <p>배치 칼럼을 하나씩 받는 이유는 생성자 식(JPQL {@code new})에 임베디드 값을 통째로 넘기는 대신
 * 칼럼 경로만 쓰기 위해서다. 네 값이 모두 null이면 기본 배치다.</p>
 */
public record BoardItemRowDto(Long boardId, Long contentId, Float x, Float y, Float rotation, Integer z) {

    /** 기본 배치면 null. 네 값은 함께 있거나 함께 없다(ck_board_item_layout). */
    public ItemLayout layout()
    {
        return x == null ? null : new ItemLayout(x, y, rotation, z);
    }
}
