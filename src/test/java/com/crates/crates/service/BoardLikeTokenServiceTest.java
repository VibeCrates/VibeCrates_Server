package com.crates.crates.service;

import com.crates.crates.Global.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoardLikeTokenServiceTest {

    private static final String SECRET = "a".repeat(48);
    private static final String OTHER_SECRET = "b".repeat(48);
    private static final List<Long> CONTENT_IDS = List.of(8L, 3L, 5L, 1L, 7L, 2L, 6L, 4L);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final BoardLikeTokenService.Purpose SEARCH = BoardLikeTokenService.Purpose.SEARCH;
    private static final BoardLikeTokenService.Purpose RECOMMEND = BoardLikeTokenService.Purpose.RECOMMEND;

    private BoardLikeTokenService service(String secret, String previous)
    {
        return new BoardLikeTokenService(objectMapper, secret, previous);
    }

    @Test
    @DisplayName("발급한 토큰을 검증하면 검색어와 콘텐츠가 배치 순서 그대로 나온다")
    void roundTrip()
    {
        BoardLikeTokenService service = service(SECRET, "");

        BoardLikeTokenService.Verified verified = service.verify(SEARCH, service.issue(SEARCH, "비 오는 날 Jazz", CONTENT_IDS));

        assertEquals("비 오는 날 Jazz", verified.title());
        assertEquals(CONTENT_IDS, verified.contentIds());
    }

    @Test
    @DisplayName("내용을 바꾼 토큰은 거절한다 — 서명이 맞지 않는다")
    void rejectsTamperedPayload()
    {
        BoardLikeTokenService service = service(SECRET, "");
        String token = service.issue(SEARCH, "재즈", CONTENT_IDS);
        String signature = token.substring(token.indexOf('.') + 1);

        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"p\":\"search-like\",\"k\":\"재즈\",\"c\":[1,2,3,4,5,6,7,9],\"iat\":0}"
                        .getBytes(StandardCharsets.UTF_8));

        assertThrows(BusinessException.class, () -> service.verify(SEARCH, forgedPayload + "." + signature));
    }

    @Test
    @DisplayName("다른 키로 만든 토큰은 거절한다")
    void rejectsOtherKey()
    {
        String token = service(OTHER_SECRET, "").issue(SEARCH, "재즈", CONTENT_IDS);

        assertThrows(BusinessException.class, () -> service(SECRET, "").verify(SEARCH, token));
    }

    @Test
    @DisplayName("키를 교체하는 동안에는 옛 키로 만든 토큰도 통과한다")
    void acceptsPreviousKeyDuringRotation()
    {
        String issuedBeforeRotation = service(OTHER_SECRET, "").issue(SEARCH, "재즈", CONTENT_IDS);

        BoardLikeTokenService.Verified verified = service(SECRET, OTHER_SECRET).verify(SEARCH, issuedBeforeRotation);

        assertEquals("재즈", verified.title());
    }

    @Test
    @DisplayName("홈 추천 보드 토큰도 같은 방식으로 발급·검증된다")
    void recommendRoundTrip()
    {
        BoardLikeTokenService service = service(SECRET, "");

        BoardLikeTokenService.Verified verified = service.verify(RECOMMEND, service.issue(RECOMMEND, "새벽 감성", CONTENT_IDS));

        assertEquals("새벽 감성", verified.title());
        assertEquals(CONTENT_IDS, verified.contentIds());
    }

    @Test
    @DisplayName("다른 용도의 토큰은 거절한다 — 검색 보드 토큰으로 AI 추천 보드를 만들 수 없고, 그 반대도 마찬가지다")
    void rejectsOtherPurpose()
    {
        BoardLikeTokenService service = service(SECRET, "");

        assertThrows(BusinessException.class, () -> service.verify(RECOMMEND, service.issue(SEARCH, "재즈", CONTENT_IDS)));
        assertThrows(BusinessException.class, () -> service.verify(SEARCH, service.issue(RECOMMEND, "재즈", CONTENT_IDS)));
    }

    @Test
    @DisplayName("형식이 깨진 토큰은 거절한다")
    void rejectsMalformed()
    {
        BoardLikeTokenService service = service(SECRET, "");

        assertThrows(BusinessException.class, () -> service.verify(SEARCH, "no-dot"));
        assertThrows(BusinessException.class, () -> service.verify(SEARCH, "a.b.c"));
        assertThrows(BusinessException.class, () -> service.verify(SEARCH, "!!!.???"));
        assertThrows(BusinessException.class, () -> service.verify(SEARCH, null));
    }

    @Test
    @DisplayName("키가 32바이트보다 짧으면 기동하지 않는다")
    void rejectsShortSecret()
    {
        assertThrows(IllegalStateException.class, () -> service("short", ""));
        assertThrows(IllegalStateException.class, () -> service(SECRET, "short"));
    }
}
