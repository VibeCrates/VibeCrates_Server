package com.crates.crates.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 보드의 최종 상태를 통째로 제출하는 요청. 생성과 수정이 같은 모양을 쓴다.
 *
 * <p>보드는 콘텐츠 8건 고정이라 개별 추가/삭제가 성립하지 않는다. 화면에 보이는 그대로
 * 제목과 8건을 보내면, 무엇이 바뀌었는지는 서버가 판단한다.</p>
 *
 * <p><b>contentIds의 인덱스가 곧 칸 순서</b>다(0번째 → slot 1). 순서 정보를 따로 받지 않으므로
 * 콘텐츠 교체와 순서 변경이 같은 요청으로 처리된다.</p>
 *
 * <p>layout은 contentIds와 같은 순서의 배치 8개이고, 없거나 null이면 기본 배치다. 기존 배치를 남기지 않고 받은 대로
 * 덮어쓰므로, 배치가 저장된 보드의 제목만 고칠 때도 프론트는 화면에 보이는 배치를 함께 보낸다(docs/board-schema.md 12-3).</p>
 */
public record BoardSaveRequest(
        @NotBlank(message = "보드 제목을 입력해주세요.")
        @Size(max = 255, message = "보드 제목은 255자를 초과할 수 없습니다.")
        String title,

        @NotEmpty(message = "보드에 담긴 콘텐츠가 필요합니다.")
        List<Long> contentIds,

        List<SlotLayoutDto> layout
) {

    /** 배치 없이(기본 배치로) 저장하는 요청. */
    public BoardSaveRequest(String title, List<Long> contentIds)
    {
        this(title, contentIds, null);
    }
}
