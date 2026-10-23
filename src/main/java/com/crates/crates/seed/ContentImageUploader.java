package com.crates.crates.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * CSV의 원본 이미지 URL을 내려받아 곧바로 이미지 저장소(로컬 MinIO, 운영 S3 — S3Config)로 흘려보내고,
 * 성공한 건만 content.s3_object_key / content.image_extension에 반영한다.
 *
 * <p>시딩 마이그레이션이 쥐고 있는 커넥션을 그대로 받아 쓴다. 마이그레이션의 INSERT는
 * 아직 커밋 전이라 커넥션 풀에서 새로 꺼낸 커넥션에서는 content 행이 보이지 않기 때문에,
 * JdbcTemplate을 쓰면 UPDATE가 조용히 0건으로 끝난다.
 *
 * <p>다운로드/업로드는 순수 네트워크 I/O라 가상 스레드로 병렬화하되, JDBC Connection은
 * 스레드 안전하지 않으므로 DB 반영은 업로드가 모두 끝난 뒤 호출 스레드에서 한 번에 처리한다.
 *
 * <p><b>실패는 원인과 함께 돌려준다(Failure).</b> 원인을 남기지 않으면 "원본이 지워졌다"와 "요청 제한에 걸렸다"와
 * "느려서 시간을 넘겼다"를 가를 수 없다. 2026-10-23 운영 채우기에서 Open Library의 요청 제한(429)을 원본 삭제와
 * 구분하지 못해 직접 원인을 캐야 했다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentImageUploader {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    // 컬럼명이 s3_object_key가 아니라 s3object_key인 건 오타가 아니다.
    // Hibernate 기본 네이밍 전략은 소문자->대문자 경계에만 _를 넣는데,
    // s3ObjectKey의 'O' 앞은 숫자 '3'이라 경계로 보지 않아 s3object_key가 된다.
    private static final String UPDATE_SQL = """
            UPDATE content
            SET s3object_key = ?, image_extension = ?
            WHERE id = ?
            """;

    private final S3Client s3Client;

    @Value("${storage.bucket-name}")
    private String bucketName;

    /** 타입당 업로드 상한. 0이면 업로드 자체를 건너뛴다(이미지 없이 시딩만). */
    @Value("${seed.image-upload.limit:0}")
    private int limit;

    /** 외부 이미지 서버에 한꺼번에 몰리지 않도록 제한하는 동시 요청 수. */
    @Value("${seed.image-upload.concurrency:30}")
    private int concurrency;

    /**
     * 한 건을 내려받는 데 쓰는 최대 시간(연결 + 응답 + 본문 전체).
     *
     * <p>HttpRequest.timeout은 응답 헤더가 올 때까지만 잰다. 본문을 아주 느리게 보내는 서버(예: archive.org로 넘기는
     * coverartarchive)에서는 요청이 끝없이 붙잡힐 수 있어, 본문까지 받은 뒤를 기준으로 따로 끊는다.</p>
     */
    @Value("${seed.image-upload.request-timeout:30s}")
    private Duration requestTimeout;

    // 여러 건을 처리하므로 클라이언트는 한 번만 만들어 재사용한다.
    // h2c 협상에서 본문이 비어 오는 사례를 겪은 적이 있어 HTTP/1.1로 고정.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public record ImageTarget(long contentId, String sourceUrl) {
    }

    /** 이미지를 못 올린 원인. 원인마다 대응이 다르다(다시 시도할 만한가, 출처를 바꿔야 하나, 원본이 없나). */
    public enum Failure {
        /** http(s) 주소가 아니다(예: 원본 CSV의 "no"). 원본에 이미지가 없다는 뜻 */
        INVALID_URL,
        /** 200이 아닌 응답. detail에 상태 코드(404 원본 삭제, 429 요청 제한, 5xx 원본 서버 오류) */
        HTTP_STATUS,
        /** request-timeout 안에 다 받지 못했다. 느린 서버면 시간을 늘려 다시 시도할 만하다 */
        TIMEOUT,
        /** 연결 실패·끊김 등 네트워크 오류 */
        NETWORK,
        /** 받았지만 jpg·png·gif·webp가 아니다. detail에 Content-Type */
        UNSUPPORTED_TYPE,
        /** 저장소(S3·MinIO)에 올리다 실패했다 */
        UPLOAD_ERROR,
        /** 작업이 중단됐다 */
        INTERRUPTED
    }

    public record FailedTarget(long contentId, String sourceUrl, Failure reason, String detail) {
    }

    /** 한 묶음의 결과. 대상 = uploaded + failures */
    public record BatchResult(Set<Long> uploaded, List<FailedTarget> failures) {

        public Map<Failure, Long> countByReason() {
            Map<Failure, Long> counts = new EnumMap<>(Failure.class);
            failures.forEach(f -> counts.merge(f.reason(), 1L, Long::sum));
            return counts;
        }
    }

    private record UploadResult(long contentId, String objectKey, String extension) {
    }

    /** 한 건의 결과. 성공(result)이거나 실패(failure) 중 하나다. */
    private record Outcome(UploadResult result, FailedTarget failure) {

        static Outcome success(UploadResult result) {
            return new Outcome(result, null);
        }

        static Outcome fail(ImageTarget target, Failure reason, String detail) {
            return new Outcome(null, new FailedTarget(target.contentId(), target.sourceUrl(), reason, detail));
        }
    }

    /**
     * @param connection 마이그레이션이 쥐고 있는 커넥션
     * @param selectSql  (content_id, image_url) 두 컬럼을 돌려주고 LIMIT ? 하나를 받는 쿼리
     * @param keyPrefix  오브젝트 키 접두사 (book / movie / music)
     * @return 업로드에 성공해 DB에 반영된 건수
     */
    public int seedImages(Connection connection, String selectSql, String keyPrefix) throws Exception {
        if (limit <= 0) {
            log.info("[{}] seed.image-upload.limit이 0이라 이미지 업로드를 건너뜁니다.", keyPrefix);
            return 0;
        }

        List<ImageTarget> targets = fetchTargets(connection, selectSql, limit);
        if (targets.isEmpty()) {
            log.info("[{}] 업로드 대상 이미지가 없습니다.", keyPrefix);
            return 0;
        }

        BatchResult result = uploadAndApply(connection, targets, keyPrefix);
        log.info("[{}] 이미지 업로드 완료: 대상 {}건 중 {}건 성공, 실패 원인 {}",
                keyPrefix, targets.size(), result.uploaded().size(), result.countByReason());
        return result.uploaded().size();
    }

    /**
     * 주어진 대상을 내려받아 올리고, 성공한 것만 content에 반영한다.
     *
     * <p>시딩(seedImages)과 이미지 채우기 작업(ImageBackfillJob)이 함께 쓴다. 작업은 대상을 묶음으로 나눠
     * 묶음마다 부르므로, 중간에 끊겨도 반영된 묶음까지는 남는다.</p>
     *
     * @return 반영된 콘텐츠 id와, 실패한 대상의 원인
     */
    public BatchResult uploadAndApply(Connection connection, List<ImageTarget> targets, String keyPrefix) throws SQLException {
        if (targets.isEmpty()) {
            return new BatchResult(Set.of(), List.of());
        }
        ensureBucket();
        List<Outcome> outcomes = uploadAll(targets, keyPrefix);

        List<UploadResult> results = outcomes.stream().map(Outcome::result).filter(r -> r != null).toList();
        applyResults(connection, results);

        return new BatchResult(
                results.stream().map(UploadResult::contentId).collect(Collectors.toSet()),
                outcomes.stream().map(Outcome::failure).filter(f -> f != null).toList());
    }

    private List<ImageTarget> fetchTargets(Connection connection, String selectSql, int limit) throws SQLException {
        List<ImageTarget> targets = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(selectSql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    targets.add(new ImageTarget(rs.getLong(1), rs.getString(2)));
                }
            }
        }
        return targets;
    }

    /**
     * 버킷이 없으면 만든다. 로컬 MinIO를 처음 띄웠을 때를 위한 것이다.
     * 운영 S3 버킷은 인프라에서 만든다.
     */
    private void ensureBucket() {
        try {
            s3Client.headBucket(request -> request.bucket(bucketName));
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(request -> request.bucket(bucketName));
            log.info("이미지 버킷을 생성했습니다: {}", bucketName);
        }
    }

    private List<Outcome> uploadAll(List<ImageTarget> targets, String keyPrefix) {
        Semaphore permits = new Semaphore(concurrency);
        List<Future<Outcome>> futures = new ArrayList<>(targets.size());

        // close()가 제출된 작업이 모두 끝날 때까지 대기한다.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ImageTarget target : targets) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        return uploadOne(target, keyPrefix);
                    } finally {
                        permits.release();
                    }
                }));
            }
        }

        List<Outcome> outcomes = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                outcomes.add(futures.get(i).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("이미지 업로드가 중단됐습니다.", e);
            } catch (ExecutionException e) {
                // uploadOne은 예외를 던지지 않는다. 여기 오면 예상 밖의 오류라 원인을 남긴다.
                outcomes.add(Outcome.fail(targets.get(i), Failure.NETWORK, String.valueOf(e.getCause())));
            }
        }
        return outcomes;
    }

    /**
     * 한 건을 내려받아 이미지 저장소에 올린다.
     *
     * <p>실패해도 예외를 던지지 않고 원인을 돌려준다. 마이그레이션 트랜잭션 안에서 도는 코드라
     * 한 건의 실패가 올라가면 시딩 전체가 롤백된다.</p>
     */
    Outcome uploadOne(ImageTarget target, String keyPrefix) {
        URI uri = toHttpUri(target.sourceUrl());
        if (uri == null) {
            return Outcome.fail(target, Failure.INVALID_URL, target.sourceUrl());
        }

        HttpResponse<byte[]> response;
        CompletableFuture<HttpResponse<byte[]>> pending =
                httpClient.sendAsync(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        try {
            // 본문까지 다 받은 시점으로 끊는다(requestTimeout 주석). 이미지는 한 장에 수백 KB라 메모리로 받는다.
            response = pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            return Outcome.fail(target, Failure.TIMEOUT, requestTimeout.toString());
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return Outcome.fail(target, Failure.INTERRUPTED, null);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                return Outcome.fail(target, Failure.TIMEOUT, cause.getClass().getSimpleName());
            }
            return Outcome.fail(target, Failure.NETWORK, cause instanceof IOException
                    ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                    : String.valueOf(cause));
        }

        if (response.statusCode() != 200) {
            return Outcome.fail(target, Failure.HTTP_STATUS, String.valueOf(response.statusCode()));
        }

        String contentType = response.headers().firstValue("Content-Type")
                .orElse("")
                .split(";")[0]
                .trim()
                .toLowerCase(Locale.ROOT);
        String extension = resolveExtension(contentType, target.sourceUrl());
        if (extension == null) {
            return Outcome.fail(target, Failure.UNSUPPORTED_TYPE, contentType);
        }

        // ImageService가 "키 + . + 확장자"로 URL을 조립하므로, DB에 넣을 키에는
        // 확장자를 붙이지 않는다. 실제 오브젝트 이름에만 붙인다.
        String objectKey = keyPrefix + "/" + target.contentId();
        try {
            s3Client.putObject(put -> put
                            .bucket(bucketName)
                            .key(objectKey + "." + extension)
                            .contentType(contentTypeOf(extension)),
                    RequestBody.fromBytes(response.body()));
        } catch (RuntimeException e) {
            return Outcome.fail(target, Failure.UPLOAD_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return Outcome.success(new UploadResult(target.contentId(), objectKey, extension));
    }

    /** http(s) 주소면 URI, 아니면 null. 원본 CSV에는 이미지가 없을 때 "no" 같은 값이 들어 있다. */
    static URI toHttpUri(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null;
        }
        try {
            return URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void applyResults(Connection connection, List<UploadResult> results) throws SQLException {
        if (results.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(UPDATE_SQL)) {
            for (UploadResult result : results) {
                ps.setString(1, result.objectKey());
                ps.setString(2, result.extension());
                ps.setLong(3, result.contentId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Content-Type을 우선 신뢰하되, 판별이 안 되면 URL 경로 확장자로 한 번 더 시도한다. */
    private String resolveExtension(String contentType, String sourceUrl) {
        String byContentType = switch (contentType) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> null;
        };
        if (byContentType != null) {
            return byContentType;
        }
        // 일부 CDN이 정상 이미지에도 application/octet-stream을 실어 보낸다.
        return extensionFromPath(sourceUrl);
    }

    private String extensionFromPath(String sourceUrl) {
        String path = sourceUrl.split("\\?", 2)[0];
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return null;
        }
        return switch (path.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "jpg";
            case "png" -> "png";
            case "gif" -> "gif";
            case "webp" -> "webp";
            default -> null;
        };
    }

    private String contentTypeOf(String extension) {
        return switch (extension) {
            case "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }
}
