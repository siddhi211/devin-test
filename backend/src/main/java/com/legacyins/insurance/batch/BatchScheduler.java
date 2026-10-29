package com.legacyins.insurance.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.batch.scheduler-enabled", havingValue = "true")
public class BatchScheduler {
    private final BatchService batch;

    @Scheduled(cron = "0 30 1 * * *")
    public void nightly() {
        log.info("Nightly batch: {}", batch.runDaily(LocalDate.now(), "SCHEDULER"));
    }

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
    public void outbox() {
        try {
            batch.run("OUTBOX_FLUSH", LocalDate.now(), "SCHEDULER");
        } catch (RuntimeException e) {
            log.debug("Outbox flush skipped: {}", e.getMessage());
        }
    }
}
