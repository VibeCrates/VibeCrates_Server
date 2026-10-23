package com.crates.crates.seed;

import com.crates.crates.seed.ContentImageUploader.BatchResult;
import com.crates.crates.seed.ContentImageUploader.Failure;
import com.crates.crates.seed.ContentImageUploader.FailedTarget;
import com.crates.crates.seed.ContentImageUploader.ImageTarget;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 실패 원인 분류와 본문까지 포함한 시간 제한을 확인한다.
 * 원본 이미지 서버는 JDK 내장 HttpServer로 흉내 내고, 저장소(S3)와 DB 커넥션은 목으로 둔다.
 */
class ContentImageUploaderTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    private HttpServer server;
    private String base;
    private S3Client s3Client;
    private Connection connection;
    private PreparedStatement update;
    private ContentImageUploader uploader;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok.png", exchange -> respond(exchange, 200, "image/png", PNG));
        server.createContext("/octet.jpg", exchange -> respond(exchange, 200, "application/octet-stream", PNG));
        server.createContext("/page", exchange -> respond(exchange, 200, "text/html", "<html/>".getBytes()));
        server.createContext("/missing.png", exchange -> respond(exchange, 404, "text/plain", new byte[0]));
        server.createContext("/limited.png", exchange -> respond(exchange, 429, "text/plain", new byte[0]));
        // 헤더는 바로 보내고 본문은 늦게 보낸다. 헤더까지만 재는 시간 제한으로는 잡히지 않는 경우
        server.createContext("/slow-body.png", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, PNG.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(PNG, 0, 1);
                out.flush();
                Thread.sleep(3_000);
                out.write(PNG, 1, PNG.length - 1);
            } catch (Exception ignored) {
                // 클라이언트가 먼저 끊으면 쓰기가 실패한다
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        s3Client = mock(S3Client.class);
        connection = mock(Connection.class);
        update = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(update);

        uploader = new ContentImageUploader(s3Client);
        ReflectionTestUtils.setField(uploader, "bucketName", "images");
        ReflectionTestUtils.setField(uploader, "concurrency", 4);
        ReflectionTestUtils.setField(uploader, "requestTimeout", Duration.ofMillis(800));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 성공은_반영하고_실패는_원인과_함께_돌려준다() throws Exception {
        List<ImageTarget> targets = List.of(
                new ImageTarget(1, base + "/ok.png"),
                new ImageTarget(2, base + "/octet.jpg"),
                new ImageTarget(3, "no"),
                new ImageTarget(4, base + "/missing.png"),
                new ImageTarget(5, base + "/limited.png"),
                new ImageTarget(6, base + "/page"),
                new ImageTarget(7, "http://127.0.0.1:1/closed.png"));

        BatchResult result = uploader.uploadAndApply(connection, targets, "music");

        assertThat(result.uploaded()).containsExactlyInAnyOrder(1L, 2L);
        Map<Long, FailedTarget> failures = result.failures().stream()
                .collect(Collectors.toMap(FailedTarget::contentId, f -> f));
        assertThat(failures).containsOnlyKeys(3L, 4L, 5L, 6L, 7L);
        assertThat(failures.get(3L).reason()).isEqualTo(Failure.INVALID_URL);
        assertThat(failures.get(4L).reason()).isEqualTo(Failure.HTTP_STATUS);
        assertThat(failures.get(4L).detail()).isEqualTo("404");
        assertThat(failures.get(5L).detail()).isEqualTo("429");
        assertThat(failures.get(6L).reason()).isEqualTo(Failure.UNSUPPORTED_TYPE);
        assertThat(failures.get(6L).detail()).isEqualTo("text/html");
        assertThat(failures.get(7L).reason()).isEqualTo(Failure.NETWORK);
        assertThat(failures.get(7L).sourceUrl()).isEqualTo("http://127.0.0.1:1/closed.png");

        // 성공한 두 건만 올리고 DB에 반영한다
        verify(s3Client, times(2)).putObject(any(Consumer.class), any(RequestBody.class));
        verify(update, times(2)).addBatch();
        verify(update).executeBatch();
    }

    @Test
    void 본문이_늦으면_시간_초과로_끊는다() throws Exception {
        long started = System.nanoTime();
        BatchResult result = uploader.uploadAndApply(connection,
                List.of(new ImageTarget(1, base + "/slow-body.png")), "music");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(result.uploaded()).isEmpty();
        assertThat(result.failures()).singleElement()
                .extracting(FailedTarget::reason).isEqualTo(Failure.TIMEOUT);
        assertThat(elapsedMillis).isLessThan(2_500); // 본문이 끝나는 3초를 기다리지 않는다
        verify(s3Client, never()).putObject(any(Consumer.class), any(RequestBody.class));
        verify(update, never()).executeBatch();
    }

    @Test
    void 원인별로_센다() throws Exception {
        BatchResult result = uploader.uploadAndApply(connection, List.of(
                new ImageTarget(1, "no"),
                new ImageTarget(2, ""),
                new ImageTarget(3, base + "/missing.png")), "music");

        assertThat(result.countByReason())
                .containsEntry(Failure.INVALID_URL, 2L)
                .containsEntry(Failure.HTTP_STATUS, 1L)
                .hasSize(2);
    }

    @Test
    void http가_아닌_값은_URL로_보지_않는다() {
        assertThat(ContentImageUploader.toHttpUri("no")).isNull();
        assertThat(ContentImageUploader.toHttpUri(null)).isNull();
        assertThat(ContentImageUploader.toHttpUri("ftp://a/b.png")).isNull();
        assertThat(ContentImageUploader.toHttpUri("https://a.com/b c.png")).isNull();
        assertThat(ContentImageUploader.toHttpUri(" HTTPS://a.com/b.png ")).hasToString("HTTPS://a.com/b.png");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String type, byte[] body)
            throws java.io.IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
