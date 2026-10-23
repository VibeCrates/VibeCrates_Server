package com.crates.crates.job;

import com.crates.crates.seed.ContentImageUploader;
import com.crates.crates.seed.ContentImageUploader.BatchResult;
import com.crates.crates.seed.ContentImageUploader.FailedTarget;
import com.crates.crates.seed.ContentImageUploader.ImageTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 이미지가 없는 콘텐츠의 이미지를 원본 CSV의 URL에서 받아 이미지 저장소에 채우는 일회성 작업.
 *
 * <p><b>왜 따로 있나.</b> 시딩(V5~V7)은 콘텐츠를 넣으면서 이미지를 타입당 상한(seed.image-upload.limit)만큼만
 * 올렸다. 콘텐츠는 지금 상태로 고정하고 이미지만 나머지를 채운다. 콘텐츠 행은 만들지도 바꾸지도 않고,
 * s3object_key·image_extension만 채운다.</p>
 *
 * <p><b>실행.</b> 같은 앱을 웹 서버 없이 띄워 이 작업만 하고 종료한다(종료 코드 0 = 성공, 1 = 실패).
 * 운영은 backend 작업 정의를 ECS 일회성 작업으로 실행한다(infra/terraform/scripts/run-image-backfill.sh).</p>
 * <pre>
 *   job.image-backfill.enabled=true          이 작업을 켠다
 *   spring.main.web-application-type=none    웹 서버를 띄우지 않는다
 *   scheduler.enabled=false                  주기 작업(백필·토큰 정리)을 끈다 — 운영 backend가 이미 돌린다
 *   job.image-backfill.{book,movie,music}    원본 CSV 위치. s3://버킷/키, file:, classpath: 모두 받는다
 *   job.image-backfill.types                 채울 종류(book,movie,music 중 일부). 기본은 셋 다
 *   job.image-backfill.report-location       실패 목록 CSV를 남길 곳(s3://버킷/키 또는 file:경로). 비우면 남기지 않는다
 * </pre>
 *
 * <p><b>CSV는 PostgreSQL COPY로 읽는다.</b> 책 설명처럼 쉼표·따옴표·줄바꿈이 섞인 열이 있어 직접 파싱하지 않고,
 * 시딩(V5~V7)과 같은 COPY(FORMAT csv)로 임시 테이블에 넣은 뒤 SQL로 콘텐츠와 짝짓는다. 열은 헤더 이름으로 찾는다.</p>
 *
 * <p><b>이어서 하기.</b> 묶음(batch-size) 단위로 올리고 묶음마다 DB에 반영한다. 끊겨도 다시 돌리면 이미지가 없는
 * 콘텐츠만 대상이 되어 이어서 한다. 한 번 실패한 콘텐츠(원본 없음·형식 모름)는 이번 실행에서 다시 고르지 않는다.</p>
 *
 * <p><b>실패 원인을 남긴다.</b> 종류마다 끝에 원인별 건수(HTTP 상태는 코드별)를 로그로 남기고, 실패 목록 전체를
 * report-location에 CSV로 남긴다. 원본 삭제(404)·요청 제한(429)·시간 초과는 대응이 달라 섞어 세면 안 된다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE) // 로컬에서는 벡터 로더(ApplicationRunner)가 먼저 끝나게 맨 뒤에 돈다
@ConditionalOnProperty(name = "job.image-backfill.enabled", havingValue = "true")
public class ImageBackfillJob implements ApplicationRunner {

    /**
     * 콘텐츠 종류마다 원본 CSV에서 content.source_key와 짝지을 열, 이미지 URL 열.
     * 시딩(V5~V7)이 source_key에 넣은 값과 같은 열이다(책은 isbn이 아니라 asin).
     */
    enum Source {
        BOOK("BOOK", "book", "asin", "imgUrl"),
        MOVIE("MOVIE", "movie", "imdbId", "Poster"),
        MUSIC("MUSIC", "music", "id", "img");

        final String dtype;
        final String keyPrefix;
        final String keyColumn;
        final String urlColumn;

        Source(String dtype, String keyPrefix, String keyColumn, String urlColumn) {
            this.dtype = dtype;
            this.keyPrefix = keyPrefix;
            this.keyColumn = keyColumn;
            this.urlColumn = urlColumn;
        }
    }

    /**
     * 이번 실행에서 실패한 콘텐츠와 원인. 같은 실행 안에서 다시 고르지 않고, 끝에 원인별로 세어 보고서로 남긴다.
     * 실행 한 번 동안만 필요해 세션 임시 테이블로 둔다.
     */
    private static final String CREATE_FAILED_TABLE = """
            CREATE TEMP TABLE IF NOT EXISTS image_backfill_failed (
                content_id BIGINT PRIMARY KEY,
                type       TEXT NOT NULL,
                reason     TEXT NOT NULL,
                detail     TEXT,
                url        TEXT
            )
            """;

    private static final String INSERT_FAILED_SQL = """
            INSERT INTO image_backfill_failed (content_id, type, reason, detail, url)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    /** HTTP 상태는 코드별로 나눠 센다(404 원본 삭제와 429 요청 제한은 대응이 다르다). */
    private static final String FAILURE_COUNT_SQL = """
            SELECT reason || CASE WHEN reason = 'HTTP_STATUS' THEN ' ' || detail ELSE '' END AS label, count(*)
              FROM image_backfill_failed
             WHERE type = ?
             GROUP BY 1
             ORDER BY 2 DESC
            """;

    private static final String REPORT_SQL = """
            COPY (
                SELECT f.type, f.content_id, c.source_key, f.reason, f.detail, f.url
                  FROM image_backfill_failed f
                  JOIN content c ON c.id = f.content_id
                 ORDER BY f.type, f.reason, f.content_id
            ) TO STDOUT WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')
            """;

    private static final String TARGET_SQL = """
            SELECT c.id, s.url
              FROM content c
              JOIN image_backfill_source s ON s.source_key = c.source_key
             WHERE c.dtype = ?
               AND c.s3object_key IS NULL
               AND NOT EXISTS (SELECT 1 FROM image_backfill_failed f WHERE f.content_id = c.id)
             ORDER BY c.id
             LIMIT ?
            """;

    private static final String REMAINING_SQL = """
            SELECT count(*) FILTER (WHERE c.s3object_key IS NULL AND s.url IS NOT NULL) AS fillable,
                   count(*) FILTER (WHERE c.s3object_key IS NULL AND s.url IS NULL)     AS no_source_url,
                   count(*) FILTER (WHERE c.s3object_key IS NOT NULL)                   AS has_image
              FROM content c
              LEFT JOIN image_backfill_source s ON s.source_key = c.source_key
             WHERE c.dtype = ?
            """;

    private final DataSource dataSource;
    private final ContentImageUploader uploader;
    private final S3Client s3Client;
    private final ResourceLoader resourceLoader;
    private final ConfigurableApplicationContext context;

    @Value("${job.image-backfill.book}")
    private String bookCsv;

    @Value("${job.image-backfill.movie}")
    private String movieCsv;

    @Value("${job.image-backfill.music}")
    private String musicCsv;

    /** 한 번에 내려받아 올리고 DB에 반영하는 수. 끊겼을 때 다시 받는 양의 상한이기도 하다. */
    @Value("${job.image-backfill.batch-size:2000}")
    private int batchSize;

    /** 종류마다 이번 실행에서 처리할 상한. 0이면 전부. 시험 삼아 적게 돌려 볼 때 쓴다. */
    @Value("${job.image-backfill.limit-per-type:0}")
    private int limitPerType;

    /** 채울 종류. 예) 책은 출처를 바꿔 따로 돌리고 영화·음악만 다시 돌릴 때 music,movie */
    @Value("${job.image-backfill.types:book,movie,music}")
    private Set<String> types;

    /** 실패 목록 CSV를 남길 곳. 비우면 남기지 않는다. */
    @Value("${job.image-backfill.report-location:}")
    private String reportLocation;

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            execute(connection, CREATE_FAILED_TABLE);

            for (Source source : selectedSources()) {
                runFor(connection, source, locationOf(source));
            }
            writeReport(connection);
        } catch (Exception e) {
            log.error("[image-backfill] 작업이 실패했습니다.", e);
            exitCode = 1;
        }

        // 웹 서버가 없어도 스레드 풀·커넥션 풀이 살아 있어 저절로 끝나지 않는다. 종료 코드를 남기고 끝낸다.
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    private List<Source> selectedSources() {
        Set<String> normalized = types.stream().map(t -> t.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        List<Source> selected = Arrays.stream(Source.values()).filter(s -> normalized.contains(s.keyPrefix)).toList();
        if (selected.size() != normalized.size()) {
            throw new IllegalStateException("job.image-backfill.types에 모르는 종류가 있습니다: " + types
                    + " (book, movie, music 중에서 고른다)");
        }
        return selected;
    }

    private String locationOf(Source source) {
        return switch (source) {
            case BOOK -> bookCsv;
            case MOVIE -> movieCsv;
            case MUSIC -> musicCsv;
        };
    }

    private void runFor(Connection connection, Source source, String location) throws Exception {
        long started = System.nanoTime();
        int staged = stage(connection, source, location);
        log.info("[image-backfill][{}] 원본 {}건(URL 있는 고유 키)을 읽었습니다: {}", source.keyPrefix, staged, location);
        logRemaining(connection, source, "시작");

        int processed = 0;
        int succeeded = 0;
        while (limitPerType <= 0 || processed < limitPerType) {
            int size = (limitPerType <= 0) ? batchSize : Math.min(batchSize, limitPerType - processed);
            List<ImageTarget> targets = fetchTargets(connection, source, size);
            if (targets.isEmpty()) {
                break;
            }

            BatchResult result = uploader.uploadAndApply(connection, targets, source.keyPrefix);
            recordFailures(connection, source, result.failures());

            processed += targets.size();
            succeeded += result.uploaded().size();
            double seconds = (System.nanoTime() - started) / 1e9;
            log.info("[image-backfill][{}] 진행: 처리 {}건 / 성공 {}건 / 실패 {}건 ({}초, 초당 {}건)",
                    source.keyPrefix, processed, succeeded, processed - succeeded,
                    Math.round(seconds), Math.round(processed / Math.max(seconds, 1)));
        }

        logRemaining(connection, source, "끝");
        logFailures(connection, source);
        execute(connection, "DROP TABLE IF EXISTS image_backfill_source");
    }

    /**
     * CSV를 임시 테이블로 읽어 (source_key, url) 짝만 남긴다.
     *
     * <p>헤더로 열 수와 위치를 알아내 모든 열을 TEXT로 받는 표를 만들고 COPY로 그대로 넣는다. 열을 이름으로 찾으므로
     * CSV의 열 순서가 바뀌어도 된다. 같은 키가 여러 행이면 URL이 있는 첫 행을 쓴다(시딩의 중복 제거와 같은 규칙).</p>
     *
     * @return 짝지을 수 있는 고유 키 수
     */
    private int stage(Connection connection, Source source, String location) throws Exception {
        List<String> header = readHeader(location);
        int keyIndex = header.indexOf(source.keyColumn);
        int urlIndex = header.indexOf(source.urlColumn);
        if (keyIndex < 0 || urlIndex < 0) {
            throw new IllegalStateException("[" + source.keyPrefix + "] CSV 헤더에 " + source.keyColumn + " 또는 "
                    + source.urlColumn + " 열이 없습니다: " + header);
        }

        StringBuilder columns = new StringBuilder();
        for (int i = 0; i < header.size(); i++) {
            columns.append(i == 0 ? "" : ", ").append("c").append(i).append(" TEXT");
        }
        execute(connection, "DROP TABLE IF EXISTS image_backfill_raw");
        execute(connection, "DROP TABLE IF EXISTS image_backfill_source");
        execute(connection, "CREATE TEMP TABLE image_backfill_raw (" + columns + ")");

        try (InputStream in = open(location)) {
            connection.unwrap(PGConnection.class).getCopyAPI()
                    .copyIn("COPY image_backfill_raw FROM STDIN WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')", in);
        }

        String key = "btrim(c" + keyIndex + ")";
        String url = "btrim(c" + urlIndex + ")";
        execute(connection, """
                CREATE TEMP TABLE image_backfill_source AS
                SELECT DISTINCT ON (%1$s) %1$s AS source_key, %2$s AS url
                  FROM image_backfill_raw
                 WHERE NULLIF(%1$s, '') IS NOT NULL
                 ORDER BY %1$s, (NULLIF(%2$s, '') IS NULL)
                """.formatted(key, url));
        // URL이 없는 키도 남겨 둔다. "원본에 URL이 없어 못 채운 수"를 따로 세기 위해서다(REMAINING_SQL).
        // 음악 CSV는 이미지가 없으면 "no"를 넣어 두었다. http(s)가 아닌 값은 내려받을 것이 없으니 URL 없음으로 친다.
        int notHttp = executeUpdate(connection,
                "UPDATE image_backfill_source SET url = NULL WHERE url !~* '^https?://'");
        if (notHttp > 0) {
            log.info("[image-backfill][{}] http(s)가 아닌 이미지 값 {}건은 원본에 URL 없음으로 칩니다(예: \"no\").",
                    source.keyPrefix, notHttp);
        }
        execute(connection, "CREATE INDEX ON image_backfill_source (source_key)");
        execute(connection, "DROP TABLE image_backfill_raw");
        execute(connection, "ANALYZE image_backfill_source");

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM image_backfill_source WHERE url IS NOT NULL")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** 헤더 한 줄. 세 CSV 모두 헤더 칸에 쉼표·따옴표가 없어 쉼표로 나눈다. */
    private List<String> readHeader(String location) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(open(location), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) {
                throw new IllegalStateException("CSV가 비어 있습니다: " + location);
            }
            if (line.startsWith("﻿")) {
                line = line.substring(1); // BOM
            }
            return Arrays.stream(line.split(",", -1)).map(String::trim).toList();
        }
    }

    /** s3://버킷/키는 S3에서, 그 밖(file:, classpath:)은 스프링 Resource로 연다. */
    private InputStream open(String location) throws IOException {
        if (location.startsWith("s3://")) {
            URI uri = URI.create(location);
            String key = uri.getPath().replaceFirst("^/", "");
            return s3Client.getObject(request -> request.bucket(uri.getHost()).key(key));
        }
        return resourceLoader.getResource(location).getInputStream();
    }

    private List<ImageTarget> fetchTargets(Connection connection, Source source, int limit) throws SQLException {
        List<ImageTarget> targets = new ArrayList<>(limit);
        try (PreparedStatement ps = connection.prepareStatement(TARGET_SQL)) {
            ps.setString(1, source.dtype);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    targets.add(new ImageTarget(rs.getLong(1), rs.getString(2)));
                }
            }
        }
        return targets;
    }

    private void recordFailures(Connection connection, Source source, List<FailedTarget> failures) throws SQLException {
        if (failures.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(INSERT_FAILED_SQL)) {
            for (FailedTarget failure : failures) {
                ps.setLong(1, failure.contentId());
                ps.setString(2, source.keyPrefix);
                ps.setString(3, failure.reason().name());
                ps.setString(4, failure.detail());
                ps.setString(5, failure.sourceUrl());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void logFailures(Connection connection, Source source) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(FAILURE_COUNT_SQL)) {
            ps.setString(1, source.keyPrefix);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString(1), rs.getLong(2));
                }
            }
        }
        log.info("[image-backfill][{}] 실패 원인: {}", source.keyPrefix, counts.isEmpty() ? "없음" : counts);
    }

    /** 실패 목록 전체를 CSV로 남긴다(종류, content_id, source_key, 원인, 상세, URL). */
    private void writeReport(Connection connection) throws Exception {
        if (reportLocation == null || reportLocation.isBlank()) {
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long rows = connection.unwrap(PGConnection.class).getCopyAPI().copyOut(REPORT_SQL, out);
        byte[] csv = out.toByteArray();

        if (reportLocation.startsWith("s3://")) {
            URI uri = URI.create(reportLocation);
            String key = uri.getPath().replaceFirst("^/", "");
            s3Client.putObject(request -> request.bucket(uri.getHost()).key(key).contentType("text/csv"),
                    RequestBody.fromBytes(csv));
        } else {
            Path path = resourceLoader.getResource(reportLocation).getFile().toPath();
            Files.write(path, csv);
        }
        log.info("[image-backfill] 실패 목록 {}건을 남겼습니다: {}", rows, reportLocation);
    }

    private void logRemaining(Connection connection, Source source, String when) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(REMAINING_SQL)) {
            ps.setString(1, source.dtype);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                log.info("[image-backfill][{}] {}: 이미지 있음 {}건 / 채울 수 있음 {}건 / 원본에 URL 없음 {}건",
                        source.keyPrefix, when, rs.getLong("has_image"), rs.getLong("fillable"), rs.getLong("no_source_url"));
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int executeUpdate(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }
}
