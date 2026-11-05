package com.crates.crates.controller;

import com.crates.crates.DTO.ApiResponse;
import com.crates.crates.DTO.BoardCaptureRequest;
import com.crates.crates.service.BoardService;
import com.crates.crates.user.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서버가 스스로 알 수 없는 사용자 행동을 프론트가 알려 주는 경로(docs/board-schema.md 13장).
 * 좋아요·보드 수정은 그 요청을 처리하면서 서버가 직접 남기므로 여기로 오지 않는다.
 */
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class UserEventController {

    private final BoardService boardService;

    // 보드 사진 저장. 캡처는 브라우저에서만 일어나므로 사용자가 "캡처하기"를 눌렀을 때 프론트가 보낸다(13-5).
    // 로그용이라 프론트는 실패해도 사용자에게 알리지 않는다.
    @PostMapping("/board-capture")
    public ResponseEntity<ApiResponse<Void>> logBoardCapture(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody BoardCaptureRequest request)
    {
        boardService.logBoardCapture(request, userDetails.getUserId());
        return ResponseEntity.ok(new ApiResponse<>(true, null, "보드 사진 저장 기록 성공"));
    }
}
