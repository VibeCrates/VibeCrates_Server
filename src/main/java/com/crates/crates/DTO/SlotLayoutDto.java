package com.crates.crates.DTO;

import com.crates.crates.entity.board.ItemLayout;

import java.util.List;

/**
 * 포스터 한 장의 배치. 보드 저장 요청과 보드 응답이 같은 모양을 쓴다(docs/board-schema.md 12-4).
 *
 * <p>보드 단위로는 8개 목록이거나 null이다. null이면 기본 배치다. 값의 범위는 {@code Board.replaceContents}가 검사한다.</p>
 *
 * @param x        포스터 중심의 가로 위치. 보드 폭 대비 % (0~100)
 * @param y        포스터 중심의 세로 위치. 보드 높이 대비 % (0~100)
 * @param rotation 기울기(도, -180~180)
 * @param z        겹침 순위(1~8, 클수록 위)
 */
public record SlotLayoutDto(Float x, Float y, Float rotation, Integer z) {

    public static List<SlotLayoutDto> fromEntities(List<ItemLayout> layout)
    {
        return layout == null ? null
                : layout.stream().map(item -> new SlotLayoutDto(item.x(), item.y(), item.rotation(), item.z())).toList();
    }

    public static List<ItemLayout> toEntities(List<SlotLayoutDto> layout)
    {
        // 빈 칸(null)은 그대로 넘겨 Board가 "배치 값이 비어 있습니다"로 거절하게 한다.
        return layout == null ? null
                : layout.stream()
                        .map(item -> item == null ? null : new ItemLayout(item.x(), item.y(), item.rotation(), item.z()))
                        .toList();
    }
}
