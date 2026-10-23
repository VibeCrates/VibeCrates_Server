package com.crates.crates.job;

import com.crates.crates.seed.ContentImageUploader;
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
import software.amazon.awssdk.services.s3.S3Client;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

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
 * </pre>
 *
 * <p><b>CSV는 PostgreSQL COPY로 읽는다.</b> 책 설명처럼 쉼표·따옴표·줄바꿈이 섞인 열이 있어 직접 파싱하지 않고,
 * 시딩(V5~V7)과 같은 COPY(FORMAT csv)로 임시 테이블에 넣은 뒤 SQL로 콘텐츠와 짝짓는다. 열은 헤더 이름으로 찾는다.</p>
 *
 * <p><b>이어서 하기.</b> 묶음(batch-size) 단위로 올리고 묶음마다 DB에 반영한다. 끊겨도 다시 돌리면 이미지가 없는
 * 콘텐츠만 대상이 되어 이어서 한다. 한 번 실패한 콘텐츠(원본 없음·형식 모름)는 이번 실행에서 다시 고르지 않는다.</p>
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

    /** 이번 실행에서 실패한 콘텐츠. 같은 실행 안에서 다시 고르지 않으려는 것이라 세션 임시 테이블로 둔다. */
    private static final String CREATE_FAILED_TABLE =
            "CREATE TEMP TABLE IF NOT EXISTS image_backfill_failed (content_id BIGINT PRIMARY KEY)";

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

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            execute(connection, CREATE_FAILED_TABLE);

            runFor(connection, Source.BOOK, bookCsv);
            runFor(connection, Source.MOVIE, movieCsv);
            runFor(connection, Source.MUSIC, musicCsv);
        } catch (Exception e) {
            log.error("[image-backfill] 작업이 실패했습니다.", e);
            exitCode = 1;
        }

        // 웹 서버가 없어도 스레드 풀·커넥션 풀이 살아 있어 저절로 끝나지 않는다. 종료 코드를 남기고 끝낸다.
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
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

            Set<Long> uploaded = uploader.uploadAndApply(connection, targets, source.keyPrefix);
            recordFailures(connection, targets, uploaded);

            processed += targets.size();
            succeeded += uploaded.size();
            double seconds = (System.nanoTime() - started) / 1e9;
            log.info("[image-backfill][{}] 진행: 처리 {}건 / 성공 {}건 / 실패 {}건 ({}초, 초당 {}건)",
                    source.keyPrefix, processed, succeeded, processed - succeeded,
                    Math.round(seconds), Math.round(processed / Math.max(seconds, 1)));
        }

        logRemaining(connection, source, "끝");
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
        // URL이 빈 키도 남겨 둔다. "원본에 URL이 없어 못 채운 수"를 따로 세기 위해서다(REMAINING_SQL).
        execute(connection, "UPDATE image_backfill_source SET url = NULL WHERE url = ''");
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

    private void recordFailures(Connection connection, List<ImageTarget> targets, Set<Long> uploaded) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO image_backfill_failed (content_id) VALUES (?) ON CONFLICT DO NOTHING")) {
            for (ImageTarget target : targets) {
                if (!uploaded.contains(target.contentId())) {
                    ps.setLong(1, target.contentId());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
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
}
