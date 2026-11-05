package com.crates.crates.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 보드 사진 저장 로그 요청(docs/board-schema.md 13-5). 사용자가 "캡처하기"를 눌렀을 때만 프론트가 보낸다.
 *
 * <p>원래 보드는 boardId(저장된 보드) 또는 likeToken(저장되지 않은 홈 추천·검색 보드)으로 가리킨다. 둘 다 있으면 boardId를 쓴다.
 * 제목과 콘텐츠는 <b>화면에 보이던 값</b>이다. 저장하지 않은 편집 상태일 수 있어 서버가 원래 보드와 비교해 표시한다.</p>
 *
 * @param contentIds 화면에 보이던 콘텐츠, 칸 순서. 검색 결과는 8건보다 적을 수 있다
 */
public record BoardCaptureRequest(
        Long boardId,

        String likeToken,

        @NotBlank(message = "보드 제목이 필요합니다.")
        @Size(max = 255, message = "보드 제목은 255자를 초과할 수 없습니다.")
        String title,

        @NotEmpty(message = "보드에 담긴 콘텐츠가 필요합니다.")
        @Size(max = 8, message = "보드에는 콘텐츠를 8건까지 담을 수 있습니다.")
        List<Long> contentIds
) {
}
