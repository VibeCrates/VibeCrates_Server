package com.crates.crates.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentImageUploader {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

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

    // 여러 건을 처리하므로 클라이언트는 한 번만 만들어 재사용한다.
    // h2c 협상에서 본문이 비어 오는 사례를 겪은 적이 있어 HTTP/1.1로 고정.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public record ImageTarget(long contentId, String sourceUrl) {
    }

    private record UploadResult(long contentId, String objectKey, String extension) {
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

        Set<Long> uploaded = uploadAndApply(connection, targets, keyPrefix);
        log.info("[{}] 이미지 업로드 완료: 대상 {}건 중 {}건 성공", keyPrefix, targets.size(), uploaded.size());
        return uploaded.size();
    }

    /**
     * 주어진 대상을 내려받아 올리고, 성공한 것만 content에 반영한다.
     *
     * <p>시딩(seedImages)과 이미지 채우기 작업(ImageBackfillJob)이 함께 쓴다. 작업은 대상을 묶음으로 나눠
     * 묶음마다 부르므로, 중간에 끊겨도 반영된 묶음까지는 남는다.</p>
     *
     * @return DB에 반영된 콘텐츠 id. 대상에 있는데 여기 없으면 실패한 것이다(원본 없음·형식 모름·네트워크 오류)
     */
    public Set<Long> uploadAndApply(Connection connection, List<ImageTarget> targets, String keyPrefix) throws SQLException {
        if (targets.isEmpty()) {
            return Set.of();
        }
        ensureBucket();
        List<UploadResult> results = uploadAll(targets, keyPrefix);
        applyResults(connection, results);
        return results.stream().map(UploadResult::contentId).collect(Collectors.toSet());
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
     * 운영 S3 버킷은 인프라에서 만들고, 운영은 업로드 상한이 0이라 여기까지 오지 않는다.
     */
    private void ensureBucket() {
        try {
            s3Client.headBucket(request -> request.bucket(bucketName));
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(request -> request.bucket(bucketName));
            log.info("이미지 버킷을 생성했습니다: {}", bucketName);
        }
    }

    private List<UploadResult> uploadAll(List<ImageTarget> targets, String keyPrefix) {
        Semaphore permits = new Semaphore(concurrency);
        List<Future<Optional<UploadResult>>> futures = new ArrayList<>(targets.size());

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

        List<UploadResult> results = new ArrayList<>(futures.size());
        for (Future<Optional<UploadResult>> future : futures) {
            try {
                future.get().ifPresent(results::add);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("이미지 업로드가 중단됐습니다.", e);
            } catch (ExecutionException e) {
                log.debug("이미지 업로드 작업이 비정상 종료됐습니다.", e.getCause());
            }
        }
        return results;
    }

    /**
     * 한 건을 내려받아 이미지 저장소에 올린다. 응답 스트림을 그대로 putObject에 넘기므로
     * 로컬 디스크에 떨어지지 않는다.
     *
     * <p>실패해도 예외를 던지지 않는다. 마이그레이션 트랜잭션 안에서 도는 코드라
     * 한 건의 실패가 올라가면 시딩 전체가 롤백된다.
     */
    private Optional<UploadResult> uploadOne(ImageTarget target, String keyPrefix) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(target.sourceUrl()))
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .build();

        try {
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    log.debug("이미지 다운로드 실패 (content {}): HTTP {}",
                            target.contentId(), response.statusCode());
                    return Optional.empty();
                }

                String contentType = response.headers().firstValue("Content-Type")
                        .orElse("")
                        .split(";")[0]
                        .trim()
                        .toLowerCase(Locale.ROOT);

                String extension = resolveExtension(contentType, target.sourceUrl());
                if (extension == null) {
                    log.debug("확장자를 판별하지 못했습니다 (content {}): contentType={}",
                            target.contentId(), contentType);
                    return Optional.empty();
                }

                // S3 PutObject는 길이를 먼저 알아야 한다. Content-Length가 오면 스트림을 그대로 흘려보내고,
                // 없으면 메모리에 모은다. 포스터·앨범 커버라 한 건이 수백 KB 수준이다.
                long size = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                RequestBody requestBody = (size >= 0)
                        ? RequestBody.fromInputStream(body, size)
                        : RequestBody.fromBytes(body.readAllBytes());

                // ImageService가 "키 + . + 확장자"로 URL을 조립하므로, DB에 넣을 키에는
                // 확장자를 붙이지 않는다. 실제 오브젝트 이름에만 붙인다.
                String objectKey = keyPrefix + "/" + target.contentId();

                s3Client.putObject(put -> put
                                .bucket(bucketName)
                                .key(objectKey + "." + extension)
                                .contentType(contentTypeOf(extension)),
                        requestBody);

                return Optional.of(new UploadResult(target.contentId(), objectKey, extension));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("이미지 처리 실패 (content {}): {}", target.contentId(), e.toString());
            return Optional.empty();
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
