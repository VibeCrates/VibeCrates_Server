package com.crates.crates.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 실제 보안 필터 체인에서 소셜 로그인 시작이 서버 세션 없이 쿠키만 남기는지 본다.
 *
 * <p>@ActiveProfiles("benchmark")는 CratesApplicationTests와 같은 이유로 지우지 말 것(ddl-auto를 none으로 고정한다).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("benchmark")
class OAuth2LoginCookieFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HttpCookieOAuth2AuthorizationRequestRepository repository;

    @Test
    @DisplayName("로그인 시작은 제공자로 보내며 세션 없이 암호화한 요청 쿠키만 심고, 콜백은 그 쿠키로 요청을 되찾는다")
    void authorizationRedirectUsesCookieNotSession() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/oauth2/authorization/kakao"))
                .andReturn().getResponse();

        assertEquals(302, response.getStatus());
        String location = response.getRedirectedUrl();
        assertNotNull(location);
        assertTrue(location.startsWith("https://kauth.kakao.com/oauth/authorize"), location);

        List<String> setCookies = response.getHeaders(HttpHeaders.SET_COOKIE);
        assertTrue(setCookies.stream().anyMatch(c -> c.startsWith(HttpCookieOAuth2AuthorizationRequestRepository.COOKIE_NAME + "=")), setCookies.toString());
        assertTrue(setCookies.stream().noneMatch(c -> c.startsWith("JSESSIONID")), "서버 세션을 만들었다: " + setCookies);
        assertNull(mockMvc.perform(get("/oauth2/authorization/kakao")).andReturn().getRequest().getSession(false),
                "요청에 세션이 생겼다");

        // 콜백: 제공자가 state를 그대로 돌려주고, 브라우저가 쿠키를 다시 보낸다.
        // 주소에는 인코딩된 채로 있다(%3D). 제공자가 돌려줄 때는 디코딩된 값으로 받는다.
        String state = URLDecoder.decode(
                UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state"), StandardCharsets.UTF_8);
        String cookie = setCookies.stream()
                .filter(c -> c.startsWith(HttpCookieOAuth2AuthorizationRequestRepository.COOKIE_NAME + "="))
                .findFirst().orElseThrow();
        MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/oauth2/code/kakao");
        callback.setParameter("state", state);
        callback.setCookies(new Cookie(HttpCookieOAuth2AuthorizationRequestRepository.COOKIE_NAME,
                cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'))));

        OAuth2AuthorizationRequest loaded = repository.loadAuthorizationRequest(callback);
        assertNotNull(loaded);
        assertEquals(state, loaded.getState());
        assertEquals("kakao", loaded.getAttributes().get("registration_id"));
    }
}
