package com.crates.crates.oauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 소셜 로그인 중의 요청 정보(state, 돌아올 주소, PKCE·nonce)를 <b>서버 세션 대신 쿠키</b>에 둔다.
 *
 * <p>기본 구현(HttpSessionOAuth2AuthorizationRequestRepository)은 서버 메모리 세션에 둔다. 그러면 로그인을 시작한
 * 작업과 제공자에서 돌아온 콜백을 받는 작업이 다를 때 세션이 없어 로그인이 실패한다 — 무중단 배포로 옛 작업과 새 작업이
 * 함께 뜨는 1~2분, 재시작, 작업을 2개 이상으로 늘릴 때다(docs/deployment.md 2-2).</p>
 *
 * <p>쿠키 내용은 브라우저에 있으므로 그대로 믿지 않는다.</p>
 * <ul>
 *   <li><b>JSON으로 직렬화한다.</b> Java 직렬화를 쿠키에서 읽으면 조작된 바이트로 임의 객체를 만들 수 있다. Spring Security가
 *       세션 저장용으로 주는 Jackson 모듈(허용 목록 기반 타입 정보)을 쓴다.</li>
 *   <li><b>AES-GCM으로 암호화한다.</b> 키 없이는 읽을 수도(PKCE 검증값·nonce), 바꿀 수도 없다. 바꾸면 복호화가 실패해
 *       요청이 없는 것으로 본다(= 로그인 실패).</li>
 *   <li>키는 JWT 서명 키에서 용도 문자열로 파생한다. 새 비밀값을 따로 두지 않고, 두 용도의 키가 같아지지도 않는다.
 *       JWT 키를 바꾸면 그 순간 진행 중이던 로그인(최대 {@link #MAX_AGE})만 실패한다.</li>
 * </ul>
 *
 * <p>기본 구현과 같이, 콜백의 {@code state} 파라미터가 저장한 값과 같을 때만 요청을 돌려준다.</p>
 */
@Component
public class HttpCookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final String COOKIE_NAME = "oauth2_auth_request";

    /** 제공자 로그인 화면에서 머무를 수 있는 시간. 넘기면 처음부터 다시 로그인한다. */
    static final Duration MAX_AGE = Duration.ofMinutes(5);

    /**
     * 콜백 주소(/login/oauth2/code/{id})에만 보낸다. 다른 요청에는 실리지 않는다.
     * 쿠키는 /oauth2/authorization/{id} 응답에서 심지만, 경로가 달라도 심을 수 있다.
     */
    private static final String COOKIE_PATH = "/login/oauth2/code";

    private static final String KEY_PURPOSE = "crates/oauth2-authorization-request/v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public HttpCookieOAuth2AuthorizationRequestRepository(@Value("${jwt.secret}") String jwtSecret) {
        this.key = new SecretKeySpec(deriveKey(jwtSecret), "AES");
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModules(SecurityJackson2Modules.getModules(getClass().getClassLoader()));
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        if (state == null) {
            return null;
        }
        OAuth2AuthorizationRequest authorizationRequest = read(request);
        return authorizationRequest != null && state.equals(authorizationRequest.getState())
                ? authorizationRequest
                : null;
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request, HttpServletResponse response) {
        if (authorizationRequest == null) {
            expire(request, response);
            return;
        }
        String value = encrypt(serialize(authorizationRequest));
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(value, MAX_AGE, request.isSecure()).toString());
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
        // 한 번 쓰면 버린다. 실패한 콜백이어도 남겨 둘 이유가 없다.
        expire(request, response);
        return authorizationRequest;
    }

    private OAuth2AuthorizationRequest read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                byte[] json = decrypt(cookie.getValue());
                return json == null ? null : deserialize(json);
            }
        }
        return null;
    }

    /*
     * SameSite=Lax: 제공자(카카오·네이버·구글)에서 우리 사이트로 돌아오는 것은 다른 사이트에서 오는 최상위 GET 이동이라
     * Lax면 쿠키가 실리고, 다른 사이트의 숨은 요청(이미지·fetch·POST)에는 실리지 않는다. Strict면 돌아올 때 쿠키가 빠진다.
     * Secure: 요청이 HTTPS일 때 붙인다. 운영은 ALB가 넘기는 X-Forwarded-Proto로 HTTPS로 보인다(forward-headers-strategy,
     * 콜백 주소도 같은 설정에 기댄다). 로컬 http://localhost에서는 붙이지 않는다 — Safari는 http의 Secure 쿠키를 받지 않는다.
     */
    private ResponseCookie cookie(String value, Duration maxAge, boolean secure) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .build();
    }

    private void expire(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, request.isSecure()).toString());
    }

    private byte[] serialize(OAuth2AuthorizationRequest authorizationRequest) {
        try {
            return objectMapper.writeValueAsBytes(authorizationRequest);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("OAuth 로그인 요청을 쿠키에 담지 못했습니다.", e);
        }
    }

    private OAuth2AuthorizationRequest deserialize(byte[] json) {
        try {
            return objectMapper.readValue(json, OAuth2AuthorizationRequest.class);
        } catch (Exception e) {
            // 복호화까지 됐는데 못 읽는 것은 형식이 바뀐 옛 쿠키뿐이다(배포 직후). 다시 로그인하면 된다.
            return null;
        }
    }

    private String encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(COOKIE_NAME.getBytes(StandardCharsets.US_ASCII));
            byte[] sealed = cipher.doFinal(plain);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("OAuth 로그인 요청을 암호화하지 못했습니다.", e);
        }
    }

    /** 위조·손상·다른 키로 만든 값이면 null. */
    private byte[] decrypt(String value) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(value);
            if (raw.length <= IV_BYTES) {
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            cipher.updateAAD(COOKIE_NAME.getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return null;
        }
    }

    /** HMAC-SHA256(JWT 키, 용도) → AES-256 키. */
    private static byte[] deriveKey(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(KEY_PURPOSE.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("OAuth 쿠키 키를 만들지 못했습니다.", e);
        }
    }
}
