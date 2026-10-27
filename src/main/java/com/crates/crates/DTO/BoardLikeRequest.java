package com.crates.crates.DTO;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 홈 추천 보드("오늘의 추천 보드")에 좋아요를 누를 때 쓰는 요청.
 * 이 보드는 사용자에게 보여줄 때 생성만 되고 저장되지 않으므로,
 * 좋아요 시점에 프론트가 제목과 콘텐츠 구성을 그대로 넘겨줘야 한다.
 *
 * <p>boardId가 있어도 이 요청을 쓴다. 서버가 구성으로 "있으면 좋아요, 없으면 저장 후 좋아요"를 처리하므로,
 * 그 사이 좋아요가 0이 되어 보드가 지워졌어도 실패하지 않는다. 검색 보드는 토큰으로 따로 보낸다(SearchBoardLikeRequest).</p>
 */
public record BoardLikeRequest(
        @NotBlank(message = "보드 제목이 필요합니다.")
        String title,

        @NotEmpty(message = "보드에 담긴 콘텐츠가 필요합니다.")
        List<Long> contentIds
) {
}
