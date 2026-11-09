package com.crates.crates.oauth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpCookieOAuth2AuthorizationRequestRepositoryTest {

    private static final String SECRET = "a".repeat(48);
    private static final String STATE = "q2W3e4R5t6Y7u8I9o0P-state";

    private final HttpCookieOAuth2AuthorizationRequestRepository repository =
            new HttpCookieOAuth2AuthorizationRequestRepository(SECRET);

    /** 구글(OIDC)처럼 nonce가 붙은, 가장 큰 축의 요청. */
    private static OAuth2AuthorizationRequest googleRequest() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("123456789012-abcdefghijklmnopqrstuvwxyz012345.apps.googleusercontent.com")
                .redirectUri("https://crates24.com/login/oauth2/code/google")
                .scopes(Set.of("openid", "profile", "email"))
                .state(STATE)
                .additionalParameters(Map.of("nonce", "Zm9vYmFyYmF6cXV4LWhhc2hlZC1ub25jZS0wMTIzNDU2Nzg5"))
                .attributes(Map.of(
                        "registration_id", "google",
                        "nonce", "raw-nonce-0123456789abcdefghijklmnopqrstuvwxyz-0123456789"))
                .authorizationRequestUri("https://accounts.google.com/o/oauth2/v2/auth?response_type=code"
                        + "&client_id=123456789012-abcdefghijklmnopqrstuvwxyz012345.apps.googleusercontent.com"
                        + "&scope=openid%20profile%20email&state=" + STATE
                        + "&redirect_uri=https://crates24.com/login/oauth2/code/google"
                        + "&nonce=Zm9vYmFyYmF6cXV4LWhhc2hlZC1ub25jZS0wMTIzNDU2Nzg5")
                .build();
    }

    /** 저장 응답의 Set-Cookie를 콜백 요청의 쿠키로 옮긴다(브라우저가 하는 일). */
    private static MockHttpServletRequest callback(MockHttpServletResponse saved, String state) {
        String header = saved.getHeader(HttpHeaders.SET_COOKIE);
        String value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        request.setCookies(new Cookie(HttpCookieOAuth2AuthorizationRequestRepository.COOKIE_NAME, value));
        if (state != null) {
            request.setParameter("state", state);
        }
        return request;
    }

    /** 운영처럼 HTTPS 요청으로 저장한다. */
    private MockHttpServletResponse save(OAuth2AuthorizationRequest authorizationRequest) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(authorizationRequest, request, response);
        return response;
    }

    @Test
    @DisplayName("저장한 요청을 다른 서버(같은 키)가 콜백에서 그대로 꺼낸다")
    void roundTripAcrossInstances() {
        MockHttpServletResponse saved = save(googleRequest());
        HttpCookieOAuth2AuthorizationRequestRepository otherInstance =
                new HttpCookieOAuth2AuthorizationRequestRepository(SECRET);

        OAuth2AuthorizationRequest loaded = otherInstance.loadAuthorizationRequest(callback(saved, STATE));

        assertNotNull(loaded);
        OAuth2AuthorizationRequest expected = googleRequest();
        assertEquals(expected.getState(), loaded.getState());
        assertEquals(expected.getClientId(), loaded.getClientId());
        assertEquals(expected.getRedirectUri(), loaded.getRedirectUri());
        assertEquals(expected.getScopes(), loaded.getScopes());
        assertEquals(expected.getAuthorizationUri(), loaded.getAuthorizationUri());
        assertEquals(expected.getAdditionalParameters(), loaded.getAdditionalParameters());
        assertEquals(expected.getAttributes(), loaded.getAttributes());
        assertEquals(expected.getAuthorizationRequestUri(), loaded.getAuthorizationRequestUri());
    }

    @Test
    @DisplayName("HTTPS에서 쿠키는 콜백 경로에만, HttpOnly·Secure·SameSite=Lax로, 5분만 산다")
    void cookieAttributes() {
        String header = save(googleRequest()).getHeader(HttpHeaders.SET_COOKIE);

        assertTrue(header.startsWith(HttpCookieOAuth2AuthorizationRequestRepository.COOKIE_NAME + "="), header);
        assertTrue(header.contains("Path=/login/oauth2/code"), header);
        assertTrue(header.contains("Max-Age=300"), header);
        assertTrue(header.contains("HttpOnly"), header);
        assertTrue(header.contains("Secure"), header);
        assertTrue(header.contains("SameSite=Lax"), header);
    }

    @Test
    @DisplayName("로컬 http에서는 Secure를 붙이지 않는다(Safari가 받지 않는다)")
    void plainHttpWithoutSecure() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(googleRequest(), new MockHttpServletRequest(), response);

        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(!header.contains("Secure") && header.contains("HttpOnly"), header);
    }

    @Test
    @DisplayName("브라우저 쿠키 한도(4KB)에 여유 있게 들어간다")
    void fitsInBrowserCookieLimit() {
        String header = save(googleRequest()).getHeader(HttpHeaders.SET_COOKIE);
        String value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));

        assertTrue(value.length() < 3000, "쿠키 값 " + value.length() + "자");
    }

    @Test
    @DisplayName("쿠키 값은 암호문이라 state·client_id가 보이지 않는다")
    void encrypted() {
        String header = save(googleRequest()).getHeader(HttpHeaders.SET_COOKIE);
        String value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
        String decoded = new String(Base64.getUrlDecoder().decode(value));

        assertTrue(!decoded.contains(STATE) && !decoded.contains("googleusercontent"), "평문이 섞여 있다");
    }

    @Test
    @DisplayName("콜백의 state가 저장한 값과 다르거나 없으면 꺼내지 않는다")
    void stateMismatch() {
        MockHttpServletResponse saved = save(googleRequest());

        assertNull(repository.loadAuthorizationRequest(callback(saved, "attacker-state")));
        assertNull(repository.loadAuthorizationRequest(callback(saved, null)));
    }

    @Test
    @DisplayName("값을 한 글자라도 바꾸거나 다른 키로 만든 쿠키는 없는 것으로 본다")
    void tamperedOrForeignCookie() {
        MockHttpServletResponse saved = save(googleRequest());
        MockHttpServletRequest request = callback(saved, STATE);
        String value = request.getCookies()[0].getValue();
        char last = value.charAt(value.length() - 1);
        String tampered = value.substring(0, value.length() - 1) + (last == 'A' ? 'B' : 'A');
        request.setCookies(new Cookie(HttpCookieOAuth2AuthorizationRequestRepository.COOKIE_NAME, tampered));

        assertNull(repository.loadAuthorizationRequest(request));
        assertNull(new HttpCookieOAuth2AuthorizationRequestRepository("b".repeat(48))
                .loadAuthorizationRequest(callback(saved, STATE)));

        request.setCookies(new Cookie(HttpCookieOAuth2AuthorizationRequestRepository.COOKIE_NAME, "not-base64!!"));
        assertNull(repository.loadAuthorizationRequest(request));
    }

    @Test
    @DisplayName("콜백에서 꺼내면서 쿠키를 지운다")
    void removeExpiresCookie() {
        MockHttpServletResponse saved = save(googleRequest());
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthorizationRequest removed = repository.removeAuthorizationRequest(callback(saved, STATE), response);

        assertNotNull(removed);
        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(header.contains("Max-Age=0") && header.contains("Path=/login/oauth2/code"), header);
    }

    @Test
    @DisplayName("null을 저장하면 쿠키를 지운다")
    void saveNullExpires() {
        String header = save(null).getHeader(HttpHeaders.SET_COOKIE);

        assertTrue(header.contains("Max-Age=0"), header);
    }

    @Test
    @DisplayName("쿠키가 없으면 null")
    void noCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        request.setParameter("state", STATE);

        assertNull(repository.loadAuthorizationRequest(request));
    }
}
