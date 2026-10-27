package com.crates.crates.controller;

import com.crates.crates.DTO.ApiResponse;
import com.crates.crates.DTO.BoardLikeResponse;
import com.crates.crates.DTO.SearchBoardDto;
import com.crates.crates.DTO.SearchBoardLikeRequest;
import com.crates.crates.service.SearchService;
import com.crates.crates.user.CustomUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Validated
public class SearchController {

    private final SearchService searchService;

    // 홈 화면 검색. 검색어 벡터와 유사한 콘텐츠를 모아 보드 한 장으로 내려준다. 제목은 검색어다.
    // 같은 제목·구성의 검색 보드가 저장돼 있지 않으면 boardId가 null이고,
    // 사용자가 좋아요를 누르는 시점에 POST /api/search/board/likes로 저장된다. 응답의 likeToken을 그때 보낸다.
    @GetMapping("/board")
    public ResponseEntity<ApiResponse<SearchBoardDto>> searchBoard(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam @NotBlank(message = "검색어를 입력해주세요.") @Size(max = 100, message = "검색어는 100자를 초과할 수 없습니다.") String keyword)
    {
        // 좋아요 여부는 사용자마다 다르므로 보드를 만들 때 함께 판단한다.
        SearchBoardDto board = searchService.searchBoard(keyword, userDetails.getUserId());
        return ResponseEntity.ok(new ApiResponse<>(true, board, "검색 보드 조회 성공"));
    }

    // 검색 보드 좋아요. boardId가 있어도 이 경로로 검색 응답의 likeToken을 보낸다.
    // 서버가 토큰 안의 검색어·콘텐츠로 "있으면 좋아요, 없으면 저장 후 좋아요"를 처리하고 boardId를 돌려준다.
    // 취소는 다른 보드처럼 DELETE /api/boards/{boardId}/likes.
    @PostMapping("/board/likes")
    public ResponseEntity<ApiResponse<BoardLikeResponse>> likeSearchBoard(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody SearchBoardLikeRequest request)
    {
        BoardLikeResponse result = searchService.likeSearchBoard(request.likeToken(), userDetails.getUserId());
        return ResponseEntity.ok(new ApiResponse<>(true, result, "검색 보드 좋아요 성공"));
    }

    // 벡터 계산만 확인하는 디버그용 엔드포인트. 실제 화면은 /board를 쓴다.
    @GetMapping("/vector")
    public ResponseEntity<ApiResponse<Void>> requestQueryVector(
            @RequestParam @NotBlank(message = "검색어를 입력해주세요.") @Size(max = 100, message = "검색어는 100자를 초과할 수 없습니다.") String keyword)
    {
        searchService.getQueryEmbedding(keyword);
        return ResponseEntity.ok(new ApiResponse<>(true, null, "검색어 벡터 계산 완료"));
    }
}
