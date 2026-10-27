package com.crates.crates.controller;


import com.crates.crates.DTO.ApiResponse;
import com.crates.crates.DTO.BoardSaveRequest;
import com.crates.crates.DTO.BoardLikeResponse;
import com.crates.crates.DTO.BoardWithContentsDto;
import com.crates.crates.DTO.RecommendedBoardDto;
import com.crates.crates.DTO.RecommendedBoardLikeRequest;
import com.crates.crates.DTO.PageResponse;
import com.crates.crates.enumData.MyBoardFilter;
import com.crates.crates.service.BoardService;
import com.crates.crates.service.RecommendationService;
import com.crates.crates.user.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/boards")
// 쿼리 파라미터의 @Min/@Max는 클래스에 @Validated가 있어야 동작한다. 없으면 애노테이션이
// 조용히 무시돼 size=100000 같은 요청이 그대로 통과한다.
@Validated
@RequiredArgsConstructor
public class BoardController {

    private final RecommendationService recommendationService;
    private final BoardService boardService;

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<BoardWithContentsDto>> getBoard(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam Long boardId)
    {
        BoardWithContentsDto board = boardService.getBoardWithContents(boardId, userDetails.getUserId());

        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>(true, board, "보드 조회 성공"));
    }

    // 사용자 벡터값 기반 추천 보드 4개.
    // 콘텐츠 목록이 아니라 보드로 내려주는 이유는, 이미 좋아요를 눌러 저장한 보드라면
    // boardId와 좋아요 상태까지 함께 알려줘야 하트가 빈 채로 다시 그려지지 않기 때문이다.
    // 보드당 8건, 노출 개수 모두 서비스 규칙상 고정이라 파라미터를 받지 않는다.
    @GetMapping("/recommendation/user")
    public ResponseEntity<ApiResponse<List<RecommendedBoardDto>>> getRecommendation(
            @AuthenticationPrincipal CustomUserDetails userDetails)
    {
        List<RecommendedBoardDto> result = recommendationService.recommendBoards(userDetails.getUserId());

        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>(true, result, "추천 보드 조회 성공"));
    }

    //- 다른 사용자들에게 좋아요를 많이 받은 보드 (컨텐츠 요약 정보)
    @GetMapping("/liked")
    public ResponseEntity<ApiResponse<List<BoardWithContentsDto>>> getLiked(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam int n)
    {
        List<BoardWithContentsDto> result = boardService.getLikedBoards(n, userDetails.getUserId());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>(true, result, "인기 보드 조회 성공"));
    }

    /**
     * 보관함 — 내 보드를 탭별로 한 페이지씩. 홈의 "내 보드" 섹션도 이 경로를 쓴다
     * (전체 탭의 첫 페이지와 같아서 조회 경로를 나눌 이유가 없다).
     *
     * <p>size에 상한을 두는 이유: 보드 1건에 콘텐츠 8건이 딸려 오므로 size=50이면 이미
     * 콘텐츠 400건을 조립한다. 상한이 없으면 누구든 size=100000을 보내 서버를 넘어뜨릴 수 있다.
     * 프론트가 무한 스크롤로 20씩 요청하는 것과, 서버가 무엇을 허용하는지는 별개 문제다.</p>
     *
     * <p>기본 20은 한 화면을 두세 배 채우는 크기다. 더 작으면 스크롤을 조금만 내려도 요청이
     * 계속 나가고, 더 크면 대부분 보지 않을 데이터 때문에 첫 화면이 늦어진다.</p>
     */
    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<PageResponse<BoardWithContentsDto>>> getMyBoards(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(defaultValue = "ALL") MyBoardFilter filter,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "page는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size는 1 이상이어야 합니다.")
            @Max(value = 50, message = "size는 50 이하여야 합니다.") int size)
    {
        PageResponse<BoardWithContentsDto> result =
                boardService.getMyBoards(filter, page, size, userDetails.getUserId());

        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>(true, result, "내 보드 조회 성공"));
    }

    // 아직 저장되지 않은 보드(추천/검색 보드)를 사용자가 고쳤을 때. 내 보드로 새로 만든다.
    @PostMapping
    public ResponseEntity<ApiResponse<BoardWithContentsDto>> createBoard(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody BoardSaveRequest request)
    {
        BoardWithContentsDto result = boardService.createUserBoard(request, userDetails.getUserId());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, result, "보드 생성 성공"));
    }

    // 보드의 제목과 콘텐츠를 통째로 갱신한다. 배열 순서가 곧 배치 순서(slot 1~8)라,
    // 제목 변경·콘텐츠 교체·순서 변경이 모두 같은 요청으로 처리된다.
    // 내 보드가 아니면 원본은 두고 내 보드로 새로 저장해 돌려주므로, 응답의 boardId를 반영해야 한다.
    @PutMapping("/{boardId}")
    public ResponseEntity<ApiResponse<BoardWithContentsDto>> updateBoard(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long boardId,
            @Valid @RequestBody BoardSaveRequest request)
    {
        BoardWithContentsDto result =
                boardService.updateBoard(boardId, request, userDetails.getUserId());

        return ResponseEntity.ok(new ApiResponse<>(true, result, "보드 수정 성공"));
    }

    //- 이미 저장된 보드에 좋아요
    @PostMapping("/{boardId}/likes")
    public ResponseEntity<ApiResponse<BoardLikeResponse>> like(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long boardId)
    {
        BoardLikeResponse result = boardService.like(boardId, userDetails.getUserId());
        return ResponseEntity.ok(new ApiResponse<>(true, result, "좋아요 성공"));
    }

    // 내 보드 삭제. 보관함에서 주인만 할 수 있다.
    // 다른 사람의 좋아요가 남아 있으면 그 사람들에게는 계속 보이고, 마지막 좋아요가 취소될 때 완전히 지워진다.
    @DeleteMapping("/{boardId}")
    public ResponseEntity<ApiResponse<Void>> deleteBoard(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long boardId)
    {
        boardService.deleteUserBoard(boardId, userDetails.getUserId());
        return ResponseEntity.ok(new ApiResponse<>(true, null, "보드 삭제 성공"));
    }

    //- 좋아요 취소. 좋아요가 0이 되어 보드가 지워지면 응답의 boardId가 null이다.
    @DeleteMapping("/{boardId}/likes")
    public ResponseEntity<ApiResponse<BoardLikeResponse>> unlike(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long boardId)
    {
        BoardLikeResponse result = boardService.unlike(boardId, userDetails.getUserId());
        return ResponseEntity.ok(new ApiResponse<>(true, result, "좋아요 취소 성공"));
    }

    //- 홈 추천 보드(오늘의 추천 보드)에 좋아요. boardId가 있어도 이 경로로 추천 응답의 likeToken을 보낸다.
    //  서버가 토큰 안의 제목·콘텐츠로 같은 구성의 보드가 있으면 좋아요만, 없으면 저장한 뒤 좋아요를 남기고 boardId를 돌려준다.
    //  검색 보드는 POST /api/search/board/likes로 검색 응답의 토큰을 보낸다.
    @PostMapping("/likes")
    public ResponseEntity<ApiResponse<BoardLikeResponse>> likeRecommendedBoard(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody RecommendedBoardLikeRequest request)
    {
        BoardLikeResponse result = recommendationService.likeRecommendedBoard(request.likeToken(), userDetails.getUserId());
        return ResponseEntity.ok(new ApiResponse<>(true, result, "보드 저장 및 좋아요 성공"));
    }
}
