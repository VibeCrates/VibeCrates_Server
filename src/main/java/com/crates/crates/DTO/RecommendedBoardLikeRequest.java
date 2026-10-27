package com.crates.crates.DTO;

import jakarta.validation.constraints.NotBlank;

/**
 * 홈 추천 보드 좋아요 요청. 제목과 콘텐츠를 받지 않고 추천 응답의 토큰만 받는다.
 * 서버는 토큰 안의 값으로 저장하므로, 요청을 조작해 다른 제목이나 콘텐츠로 AI 추천 보드를 만들 수 없다.
 */
public record RecommendedBoardLikeRequest(
        @NotBlank(message = "추천 보드 토큰이 필요합니다.")
        String likeToken
) {
}
