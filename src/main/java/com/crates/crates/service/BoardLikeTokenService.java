package com.crates.crates.service;

import com.crates.crates.Global.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 저장 전 보드(검색 보드, 홈 추천 보드)의 좋아요 토큰. "서버가 이 제목으로 이 콘텐츠 8건을 실제로 보여 줬다"는 증명이다.
 *
 * <p>두 보드 모두 좋아요를 누를 때 저장되고, 저장된 보드는 같은 구성을 받은 모든 사람에게 같은 보드로 보인다.
 * 그래서 좋아요 요청의 제목과 콘텐츠를 프론트가 보낸 그대로 믿으면, 요청을 직접 만들어 아무 제목에 아무 콘텐츠를
 * 붙인 공개 보드를 만들 수 있다. 응답에 서명한 토큰을 실어 보내고, 좋아요 때는 <b>토큰 안의 값만</b> 쓴다.</p>
 *
 * <pre>
 *   token = base64url(payload JSON) + "." + base64url(HMAC-SHA256(비밀 키, payload JSON))
 *   payload = { "p": 용도, "k": 제목, "c": [콘텐츠 id 8건, 배치 순서], "iat": 발급 시각(초) }
 *   용도    = "search-like"(검색 보드, 제목 = 검색어) | "recommend-like"(홈 추천 보드, 제목 = 고른 query)
 * </pre>
 *
 * <ul>
 *   <li>서버가 아무것도 저장하지 않는다. 캐시가 만료되거나 다른 사람의 검색으로 결과가 바뀌어도
 *       <b>그 사용자가 본 결과</b>가 검증된다.</li>
 *   <li><b>만료를 검사하지 않는다.</b> 서명이 맞으면 오래돼도 내용은 진짜이고, 좋아요는 멱등이라 재사용해도 해가 없다.
 *       만료 후 서버가 다시 검색하면 사용자가 본 적 없는 8건에 좋아요가 붙을 수 있다. iat는 문제를 추적할 때만 쓴다.</li>
 *   <li>p(용도)를 넣어, 같은 키로 만든 다른 용도의 토큰이 서로 재사용되지 않게 한다. 검색 보드 토큰으로
 *       홈 추천 보드를 만들 수 없고, 그 반대도 마찬가지다.</li>
 *   <li>키를 교체할 때는 새 키를 secret에, 옛 키를 previous-secret에 하루쯤 둔다. 그 사이 발급된 토큰도 통과한다.</li>
 * </ul>
 *
 * <p>설계 근거는 docs/board-schema.md 11-4. 홈 추천 보드는 2026-10-28부터 같은 방식이다(11-11).</p>
 */
@Service
public class BoardLikeTokenService {

    /** 토큰의 용도. 검증할 때 발급한 용도와 같아야 통과한다. */
    public enum Purpose
    {
        SEARCH("search-like", "검색 결과를 확인할 수 없습니다. 다시 검색해 주세요."),
        RECOMMEND("recommend-like", "추천 보드를 확인할 수 없습니다. 홈을 새로고침해 주세요.");

        private final String code;
        /** 검증에 실패했을 때 사용자에게 보일 문구. 앱을 정상적으로 쓰면 겪지 않는다(키 교체 직후 정도). */
        private final String invalidMessage;

        Purpose(String code, String invalidMessage)
        {
            this.code = code;
            this.invalidMessage = invalidMessage;
        }
    }

    private static final String ALGORITHM = "HmacSHA256";

    /** 키가 이보다 짧으면 HMAC의 강도가 키 길이로 떨어진다. SHA-256 출력 길이(32바이트)에 맞춘다. */
    private static final int MIN_SECRET_BYTES = 32;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final SecretKeySpec currentKey;
    /** 키를 교체하는 동안만 값이 있다. 없으면 null. */
    private final SecretKeySpec previousKey;

    public BoardLikeTokenService(ObjectMapper objectMapper,
                                 @Value("${board.like-token.secret}") String secret,
                                 @Value("${board.like-token.previous-secret:}") String previousSecret)
    {
        this.objectMapper = objectMapper;
        this.currentKey = toKey(secret, "board.like-token.secret");
        this.previousKey = previousSecret.isBlank() ? null : toKey(previousSecret, "board.like-token.previous-secret");
    }

    /** 검증을 통과한 토큰의 내용. contentIds는 보여 준 배치 순서 그대로다. */
    public record Verified(String title, List<Long> contentIds) {
    }

    /** 토큰에 담는 값. 필드 이름을 짧게 두는 이유는 토큰이 응답마다 실려 나가기 때문이다. */
    private record Payload(String p, String k, List<Long> c, long iat) {
    }

    /**
     * 응답에 실을 토큰을 만든다.
     *
     * @param title      보여 준 제목. 저장할 제목과 같은 값이어야 한다(검색어는 {@code Board.normalizeTitle}을 거친 값)
     * @param contentIds 보여 준 콘텐츠 id, 배치 순서대로
     */
    public String issue(Purpose purpose, String title, List<Long> contentIds)
    {
        byte[] payload = toJson(new Payload(purpose.code, title, List.copyOf(contentIds), Instant.now().getEpochSecond()));
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(currentKey, payload));
    }

    /**
     * 토큰을 검증하고 내용을 꺼낸다.
     *
     * <p>실패는 서명이 맞지 않거나(내용을 바꿨거나 직접 만든 토큰), 형식이 깨졌거나, 다른 용도의 토큰인 경우다.
     * 앱을 정상적으로 쓰는 사용자는 겪지 않는다. 예외로 키를 교체하고 하루가 지나면 옛 토큰이 실패하는데,
     * 그때는 다시 검색하거나 홈을 새로고침하면 된다는 안내로 충분하다.</p>
     */
    public Verified verify(Purpose purpose, String token)
    {
        int dot = token == null ? -1 : token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.'))
        {
            throw invalid(purpose);
        }

        byte[] payload;
        byte[] signature;
        try
        {
            payload = DECODER.decode(token.substring(0, dot));
            signature = DECODER.decode(token.substring(dot + 1));
        }
        catch (IllegalArgumentException e)
        {
            throw invalid(purpose);
        }

        // 서명부터 본다. 서명이 맞기 전의 내용은 누가 만들었는지 모르는 값이라 해석하지 않는다.
        if (!matches(currentKey, payload, signature) && !(previousKey != null && matches(previousKey, payload, signature)))
        {
            throw invalid(purpose);
        }

        Payload parsed = fromJson(payload, purpose);
        if (!purpose.code.equals(parsed.p()) || parsed.k() == null || parsed.c() == null)
        {
            throw invalid(purpose);
        }
        return new Verified(parsed.k(), new ArrayList<>(parsed.c()));
    }

    private static boolean matches(SecretKeySpec key, byte[] payload, byte[] signature)
    {
        // 상수 시간 비교. equals처럼 앞에서부터 다른 곳에서 멈추면, 응답 시간 차이로 맞는 서명을 한 바이트씩 알아낼 수 있다.
        return MessageDigest.isEqual(sign(key, payload), signature);
    }

    private static byte[] sign(SecretKeySpec key, byte[] payload)
    {
        try
        {
            // Mac은 스레드 안전하지 않아 호출마다 만든다. 만드는 비용은 HMAC 계산에 비해 무시할 만하다.
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload);
        }
        catch (GeneralSecurityException e)
        {
            // HmacSHA256은 모든 JDK가 갖춰야 하는 알고리즘이라 여기 올 일이 없다.
            throw new IllegalStateException("HMAC 서명을 만들 수 없습니다.", e);
        }
    }

    private byte[] toJson(Payload payload)
    {
        try
        {
            return objectMapper.writeValueAsBytes(payload);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException("보드 좋아요 토큰을 만들 수 없습니다.", e);
        }
    }

    private Payload fromJson(byte[] payload, Purpose purpose)
    {
        try
        {
            return objectMapper.readValue(payload, Payload.class);
        }
        catch (java.io.IOException e)
        {
            throw invalid(purpose);
        }
    }

    private static SecretKeySpec toKey(String secret, String property)
    {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES)
        {
            // 키 없이 뜨면 검색·홈 추천 보드의 첫 좋아요가 모두 실패한다. 배포 직후 조용히 고장 나기보다 기동에서 막는다.
            throw new IllegalStateException(property + "는 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다. "
                    + "예: openssl rand -base64 48");
        }
        return new SecretKeySpec(bytes, ALGORITHM);
    }

    private static BusinessException invalid(Purpose purpose)
    {
        return new BusinessException(purpose.invalidMessage);
    }
}
