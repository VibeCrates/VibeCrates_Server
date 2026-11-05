package com.crates.crates.entity.board;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * 사용자가 끌어 옮기고 돌린 포스터 한 장의 배치(docs/board-schema.md 12장).
 *
 * <p>네 칼럼이 모두 NULL이면 Hibernate가 이 값 자체를 null로 읽는다. null이면 프론트의 기본 배치로 그린다.
 * 값의 범위와 "8칸이 모두 있거나 모두 없음"은 {@link Board#replaceContents}가 검사한다. 여기서 검사하지 않는 이유는,
 * DB에서 읽을 때도 이 생성자를 거치므로 검사 규칙이 바뀌면 이미 저장된 보드를 읽지 못하게 되기 때문이다.</p>
 *
 * @param x        포스터 중심의 가로 위치. 보드 폭 대비 % (0~100)
 * @param y        포스터 중심의 세로 위치. 보드 높이 대비 % (0~100)
 * @param rotation 기울기(도, -180~180)
 * @param z        겹침 순위(1~8, 클수록 위). 한 보드 안에서 한 번씩만 쓴다
 */
@Embeddable
public record ItemLayout(
        @Column(name = "pos_x") Float x,
        @Column(name = "pos_y") Float y,
        @Column(name = "rotation") Float rotation,
        @Column(name = "z_order") Integer z
) {
}
