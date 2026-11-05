package com.crates.crates.scheduler;

import com.crates.crates.service.UserEventLogExporter;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** 매일 새벽 전날까지의 행동 로그를 S3에 올린다(docs/board-schema.md 13-7). */
@Component
@RequiredArgsConstructor
public class UserEventLogExportScheduler {

    private final UserEventLogExporter userEventLogExporter;

    @Scheduled(cron = "${scheduler.event-log-export.cron}", zone = "Asia/Seoul")
    public void export() {
        userEventLogExporter.exportMissingDays(LocalDate.now(UserEventLogExporter.ZONE));
    }
}
