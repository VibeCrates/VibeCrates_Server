package com.crates.crates.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 어느 날을 올리는지(docs/board-schema.md 13-7). 파일 내용은 로컬 MinIO로 확인했다. */
class UserEventLogExporterTest {

    private final S3Client s3Client = mock(S3Client.class);
    private final UserEventLogExporter exporter = new UserEventLogExporter(
            mock(DataSource.class), mock(PlatformTransactionManager.class), s3Client, new ObjectMapper(),
            "crates-ops", "event-logs", 3);

    @Test
    @DisplayName("날짜 경로는 event-logs/dt=YYYY-MM-DD/events.jsonl.gz다")
    void keyFormat()
    {
        assertEquals("event-logs/dt=2026-11-05/events.jsonl.gz", exporter.keyOf(LocalDate.of(2026, 11, 5)));
    }

    @Test
    @DisplayName("최근 며칠이 모두 올라가 있으면 아무것도 올리지 않고, 오늘은 보지 않는다")
    void skipsDaysAlreadyExported()
    {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(call ->
        {
            String key = ((ListObjectsV2Request) call.getArgument(0)).prefix();
            return ListObjectsV2Response.builder().contents(S3Object.builder().key(key).build()).build();
        });

        exporter.exportMissingDays(LocalDate.of(2026, 11, 6));

        ArgumentCaptor<ListObjectsV2Request> checked = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3Client, times(3)).listObjectsV2(checked.capture());
        assertEquals(List.of(
                        "event-logs/dt=2026-11-03/events.jsonl.gz",
                        "event-logs/dt=2026-11-04/events.jsonl.gz",
                        "event-logs/dt=2026-11-05/events.jsonl.gz"),
                checked.getAllValues().stream().map(ListObjectsV2Request::prefix).toList());
        verify(s3Client, never()).putObject(any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),
                any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    @DisplayName("이름이 앞부분만 같은 다른 파일은 올라간 것으로 보지 않는다")
    void prefixMatchIsNotExistence()
    {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("event-logs/dt=2026-11-05/events.jsonl.gz.tmp").build()).build());

        // 올리려다 DB 목(mock)에서 실패하지만, 한 날의 실패가 나머지 날을 막지 않는다.
        exporter.exportMissingDays(LocalDate.of(2026, 11, 6));

        verify(s3Client, times(3)).listObjectsV2(any(ListObjectsV2Request.class));
    }
}
