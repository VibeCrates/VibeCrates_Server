package com.crates.crates.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.zip.GZIPOutputStream;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

/**
 * 사용자 행동 로그를 하루치씩 JSONL + gzip 파일로 S3에 올린다(docs/board-schema.md 13-7).
 *
 * <p>운영 DB에는 사용자만 접근할 수 있어, AI 개발자는 DB가 아니라 이 파일을 받아 간다. 한 줄이 이벤트 하나다.</p>
 * <pre>
 *   s3://{bucket}/{prefix}/dt=2026-11-05/events.jsonl.gz
 *   {"id":13,"userId":1,"eventType":"BOARD_UNLIKE","createdAt":"2026-11-05T23:32:33.867408+09:00","payload":{...}}
 * </pre>
 *
 * <ul>
 *   <li><b>이미 끝난 날을 통째로</b> 올린다. created_at은 트랜잭션 시작 시각이라 커밋 순서와 조금 어긋날 수 있다.
 *       "마지막으로 올린 id 이후"로 끊으면 늦게 커밋된 행을 빠뜨린다. 하루가 지나 3시간 뒤에는 그런 행이 없다.</li>
 *   <li><b>최근 며칠 중 아직 없는 날을 모두</b> 올린다. AWS를 꺼 둔 동안 거른 날은 다음 실행이 채운다.
 *       파일이 있는지로만 판단하므로 같은 날을 다시 올리지 않고, 두 번 돌아도 결과가 같다.</li>
 *   <li>이벤트가 없는 날도 빈 파일을 올린다. "그날은 0건"과 "아직 안 올림"을 구분하게 한다.</li>
 *   <li>하루치를 메모리에 올리지 않는다. DB 커서로 나눠 읽으며 바로 압축해 임시 파일에 쓰고, 다 쓴 뒤 올린다.
 *       커넥션은 읽는 동안만 쥔다 — S3 업로드는 트랜잭션 밖이다.</li>
 * </ul>
 *
 * <p>날짜 범위로 고를 인덱스가 없어 하루치마다 테이블을 한 번 훑는다. 하루 한 번, 새벽이라 감수한다(V4 머리말).</p>
 */
@Slf4j
@Service
public class UserEventLogExporter {

    /** 하루의 경계. 사용자와 AI 개발자가 보는 날짜가 한국 날짜다. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 한 번에 DB에서 받아 오는 행 수. 커서로 이만큼씩 읽어 메모리를 일정하게 둔다. */
    private static final int FETCH_SIZE = 1000;

    private static final String SELECT_DAY = """
            SELECT id, user_id, event_type, created_at, payload::text
              FROM user_event_log
             WHERE created_at >= ? AND created_at < ?
             ORDER BY id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate readOnlyTransaction;
    private final S3Client s3Client;
    private final ObjectMapper objectMapper;
    private final String bucket;
    private final String prefix;
    private final int catchUpDays;

    public UserEventLogExporter(DataSource dataSource,
                                PlatformTransactionManager transactionManager,
                                S3Client s3Client,
                                ObjectMapper objectMapper,
                                @Value("${event-log.export.bucket}") String bucket,
                                @Value("${event-log.export.prefix}") String prefix,
                                @Value("${event-log.export.catch-up-days}") int catchUpDays)
    {
        // PostgreSQL은 자동 커밋을 끈 트랜잭션 안에서만 fetch size만큼 나눠 읽는다. 아니면 결과 전체를 한 번에 받는다.
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setFetchSize(FETCH_SIZE);
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
        this.bucket = bucket;
        this.prefix = prefix;
        this.catchUpDays = catchUpDays;
    }

    /**
     * 어제부터 catchUpDays일 전까지, 아직 올리지 않은 날을 오래된 날부터 올린다.
     * 한 날이 실패해도 나머지는 계속한다. 실패한 날은 파일이 없으니 다음 실행이 다시 시도한다.
     */
    public void exportMissingDays(LocalDate today)
    {
        for (int daysAgo = catchUpDays; daysAgo >= 1; daysAgo--)
        {
            LocalDate day = today.minusDays(daysAgo);
            String key = keyOf(day);
            try
            {
                if (!exists(key))
                {
                    exportDay(day, key);
                }
            }
            catch (RuntimeException e)
            {
                log.error("행동 로그 내보내기 실패 day={} key={}", day, key, e);
            }
        }
    }

    String keyOf(LocalDate day)
    {
        return prefix + "/dt=" + day + "/events.jsonl.gz";
    }

    private boolean exists(String key)
    {
        // HeadObject는 없는 키에 403/404를 가리려면 GetObject 권한이 필요하다. 목록 조회는 ListBucket만으로 된다.
        return s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(key).maxKeys(1).build())
                .contents().stream()
                .anyMatch(object -> object.key().equals(key));
    }

    private void exportDay(LocalDate day, String key)
    {
        Path file = null;
        try
        {
            file = Files.createTempFile("user-event-log-" + day + "-", ".jsonl.gz");
            long rows = writeDay(day, file);
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            // Content-Encoding: gzip은 붙이지 않는다. 붙이면 내려받는 도구가 풀어서 저장해 .gz 이름과 내용이 어긋난다.
                            .contentType("application/gzip")
                            .build(),
                    RequestBody.fromFile(file));
            log.info("행동 로그 내보내기 완료 day={} rows={} key={}", day, rows, key);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        finally
        {
            deleteQuietly(file);
        }
    }

    /** 그날 행을 id 순으로 파일에 쓰고 행 수를 돌려준다. */
    private long writeDay(LocalDate day, Path file) throws IOException
    {
        OffsetDateTime from = day.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime to = day.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();

        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file));
             JsonGenerator generator = objectMapper.getFactory().createGenerator(out))
        {
            // 값 사이에 Jackson이 넣는 공백 대신 줄바꿈을 직접 쓴다(JSONL).
            generator.setRootValueSeparator(null);
            long[] rows = {0};
            readOnlyTransaction.executeWithoutResult(status ->
                    jdbcTemplate.query(SELECT_DAY, resultSet ->
                    {
                        writeLine(generator, resultSet);
                        rows[0]++;
                    }, from, to));
            return rows[0];
        }
    }

    private static void writeLine(JsonGenerator generator, ResultSet row) throws SQLException
    {
        try
        {
            generator.writeStartObject();
            generator.writeNumberField("id", row.getLong("id"));
            generator.writeNumberField("userId", row.getLong("user_id"));
            generator.writeStringField("eventType", row.getString("event_type"));
            // 초가 0이어도 생략하지 않는다(OffsetDateTime.toString은 "00:00+09:00"처럼 줄인다).
            generator.writeStringField("createdAt", ISO_OFFSET_DATE_TIME.format(
                    row.getObject("created_at", OffsetDateTime.class).atZoneSameInstant(ZONE)));
            // jsonb를 text로 꺼낸 값이라 이미 올바른 JSON이다. 다시 해석하지 않고 그대로 넣는다.
            generator.writeFieldName("payload");
            generator.writeRawValue(row.getString("payload"));
            generator.writeEndObject();
            generator.writeRaw('\n');
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteQuietly(Path file)
    {
        if (file == null)
        {
            return;
        }
        try
        {
            Files.deleteIfExists(file);
        }
        catch (IOException e)
        {
            log.warn("임시 파일을 지우지 못했습니다: {}", file, e);
        }
    }
}
