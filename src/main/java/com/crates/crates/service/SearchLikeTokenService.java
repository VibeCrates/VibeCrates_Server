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
 * 검색 보드 좋아요 토큰. "서버가 이 검색어로 이 콘텐츠 8건을 실제로 보여 줬다"는 증명이다.
 *
 * <p>검색 보드는 좋아요를 누를 때 저장되고, 저장된 보드는 같은 검색어·구성을 받은 모든 사람에게 같은 보드로 보인다.
 * 그래서 좋아요 요청의 제목과 콘텐츠를 프론트가 보낸 그대로 믿으면, 요청을 직접 만들어 아무 검색어에 아무 콘텐츠를
 * 붙인 공개 보드를 만들 수 있다. 검색 응답에 서명한 토큰을 실어 보내고, 좋아요 때는 <b>토큰 안의 값만</b> 쓴다.</p>
 *
 * <pre>
 *   token = base64url(payload JSON) + "." + base64url(HMAC-SHA256(비밀 키, payload JSON))
 *   payload = { "p": "search-like", "k": 검색어, "c": [콘텐츠 id 8건, 배치 순서], "iat": 발급 시각(초) }
 * </pre>
 *
 * <ul>
 *   <li>서버가 아무것도 저장하지 않는다. 캐시가 만료되거나 다른 사람의 검색으로 결과가 바뀌어도
 *       <b>그 사용자가 본 결과</b>가 검증된다.</li>
 *   <li><b>만료를 검사하지 않는다.</b> 서명이 맞으면 오래돼도 내용은 진짜이고, 좋아요는 멱등이라 재사용해도 해가 없다.
 *       만료 후 서버가 다시 검색하면 사용자가 본 적 없는 8건에 좋아요가 붙을 수 있다. iat는 문제를 추적할 때만 쓴다.</li>
 *   <li>p(용도)를 넣어, 나중에 다른 기능이 같은 키로 토큰을 만들어도 서로 재사용되지 않게 한다.</li>
 *   <li>키를 교체할 때는 새 키를 secret에, 옛 키를 previous-secret에 하루쯤 둔다. 그 사이 발급된 토큰도 통과한다.</li>
 * </ul>
 *
 * <p>설계 근거는 docs/board-schema.md 11-4.</p>
 */
@Service
public class SearchLikeTokenService {

    private static final String PURPOSE = "search-like";
    private static final String ALGORITHM = "HmacSHA256";

    /** 키가 이보다 짧으면 HMAC의 강도가 키 길이로 떨어진다. SHA-256 출력 길이(32바이트)에 맞춘다. */
    private static final int MIN_SECRET_BYTES = 32;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final SecretKeySpec currentKey;
    /** 키를 교체하는 동안만 값이 있다. 없으면 null. */
    private final SecretKeySpec previousKey;

    public SearchLikeTokenService(ObjectMapper objectMapper,
                                  @Value("${search.like-token.secret}") String secret,
                                  @Value("${search.like-token.previous-secret:}") String previousSecret)
    {
        this.objectMapper = objectMapper;
        this.currentKey = toKey(secret, "search.like-token.secret");
        this.previousKey = previousSecret.isBlank() ? null : toKey(previousSecret, "search.like-token.previous-secret");
    }

    /** 검증을 통과한 토큰의 내용. contentIds는 검색 결과의 배치 순서 그대로다. */
    public record Verified(String keyword, List<Long> contentIds) {
    }

    /** 토큰에 담는 값. 필드 이름을 짧게 두는 이유는 토큰이 응답마다 실려 나가기 때문이다. */
    private record Payload(String p, String k, List<Long> c, long iat) {
    }

    /**
     * 검색 응답에 실을 토큰을 만든다.
     *
     * @param keyword    {@code Board.normalizeTitle}을 거친 검색어. 저장할 제목과 같은 값이어야 한다
     * @param contentIds 보여 준 콘텐츠 id, 배치 순서대로
     */
    public String issue(String keyword, List<Long> contentIds)
    {
        byte[] payload = toJson(new Payload(PURPOSE, keyword, List.copyOf(contentIds), Instant.now().getEpochSecond()));
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(currentKey, payload));
    }

    /**
     * 토큰을 검증하고 내용을 꺼낸다.
     *
     * <p>실패는 서명이 맞지 않거나(내용을 바꿨거나 직접 만든 토큰), 형식이 깨졌거나, 다른 용도의 토큰인 경우다.
     * 앱을 정상적으로 쓰는 사용자는 겪지 않는다. 예외로 키를 교체하고 하루가 지나면 옛 토큰이 실패하는데,
     * 그때는 다시 검색하면 된다는 안내로 충분하다.</p>
     */
    public Verified verify(String token)
    {
        int dot = token == null ? -1 : token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.'))
        {
            throw invalid();
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
            throw invalid();
        }

        // 서명부터 본다. 서명이 맞기 전의 내용은 누가 만들었는지 모르는 값이라 해석하지 않는다.
        if (!matches(currentKey, payload, signature) && !(previousKey != null && matches(previousKey, payload, signature)))
        {
            throw invalid();
        }

        Payload parsed = fromJson(payload);
        if (!PURPOSE.equals(parsed.p()) || parsed.k() == null || parsed.c() == null)
        {
            throw invalid();
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
            throw new IllegalStateException("검색 좋아요 토큰을 만들 수 없습니다.", e);
        }
    }

    private Payload fromJson(byte[] payload)
    {
        try
        {
            return objectMapper.readValue(payload, Payload.class);
        }
        catch (java.io.IOException e)
        {
            throw invalid();
        }
    }

    private static SecretKeySpec toKey(String secret, String property)
    {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES)
        {
            // 키 없이 뜨면 모든 검색 좋아요가 실패한다. 배포 직후 조용히 고장 나기보다 기동에서 막는다.
            throw new IllegalStateException(property + "는 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다. "
                    + "예: openssl rand -base64 48");
        }
        return new SecretKeySpec(bytes, ALGORITHM);
    }

    private static BusinessException invalid()
    {
        return new BusinessException("검색 결과를 확인할 수 없습니다. 다시 검색해 주세요.");
    }
}
