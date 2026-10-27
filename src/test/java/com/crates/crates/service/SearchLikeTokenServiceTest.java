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

class SearchLikeTokenServiceTest {

    private static final String SECRET = "a".repeat(48);
    private static final String OTHER_SECRET = "b".repeat(48);
    private static final List<Long> CONTENT_IDS = List.of(8L, 3L, 5L, 1L, 7L, 2L, 6L, 4L);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SearchLikeTokenService service(String secret, String previous)
    {
        return new SearchLikeTokenService(objectMapper, secret, previous);
    }

    @Test
    @DisplayName("발급한 토큰을 검증하면 검색어와 콘텐츠가 배치 순서 그대로 나온다")
    void roundTrip()
    {
        SearchLikeTokenService service = service(SECRET, "");

        SearchLikeTokenService.Verified verified = service.verify(service.issue("비 오는 날 Jazz", CONTENT_IDS));

        assertEquals("비 오는 날 Jazz", verified.keyword());
        assertEquals(CONTENT_IDS, verified.contentIds());
    }

    @Test
    @DisplayName("내용을 바꾼 토큰은 거절한다 — 서명이 맞지 않는다")
    void rejectsTamperedPayload()
    {
        SearchLikeTokenService service = service(SECRET, "");
        String token = service.issue("재즈", CONTENT_IDS);
        String signature = token.substring(token.indexOf('.') + 1);

        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"p\":\"search-like\",\"k\":\"재즈\",\"c\":[1,2,3,4,5,6,7,9],\"iat\":0}"
                        .getBytes(StandardCharsets.UTF_8));

        assertThrows(BusinessException.class, () -> service.verify(forgedPayload + "." + signature));
    }

    @Test
    @DisplayName("다른 키로 만든 토큰은 거절한다")
    void rejectsOtherKey()
    {
        String token = service(OTHER_SECRET, "").issue("재즈", CONTENT_IDS);

        assertThrows(BusinessException.class, () -> service(SECRET, "").verify(token));
    }

    @Test
    @DisplayName("키를 교체하는 동안에는 옛 키로 만든 토큰도 통과한다")
    void acceptsPreviousKeyDuringRotation()
    {
        String issuedBeforeRotation = service(OTHER_SECRET, "").issue("재즈", CONTENT_IDS);

        SearchLikeTokenService.Verified verified = service(SECRET, OTHER_SECRET).verify(issuedBeforeRotation);

        assertEquals("재즈", verified.keyword());
    }

    @Test
    @DisplayName("형식이 깨진 토큰은 거절한다")
    void rejectsMalformed()
    {
        SearchLikeTokenService service = service(SECRET, "");

        assertThrows(BusinessException.class, () -> service.verify("no-dot"));
        assertThrows(BusinessException.class, () -> service.verify("a.b.c"));
        assertThrows(BusinessException.class, () -> service.verify("!!!.???"));
        assertThrows(BusinessException.class, () -> service.verify(null));
    }

    @Test
    @DisplayName("키가 32바이트보다 짧으면 기동하지 않는다")
    void rejectsShortSecret()
    {
        assertThrows(IllegalStateException.class, () -> service("short", ""));
        assertThrows(IllegalStateException.class, () -> service(SECRET, "short"));
    }
}
