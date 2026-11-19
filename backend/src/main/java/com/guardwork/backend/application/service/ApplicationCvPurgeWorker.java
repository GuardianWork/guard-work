package com.guardwork.backend.application.service;

import com.guardwork.backend.logging.DiagnosticEvent;
import com.guardwork.backend.logging.DiagnosticLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "application.cv.purge.worker-enabled",
        havingValue = "true", matchIfMissing = true)
public class ApplicationCvPurgeWorker {

    private static final Logger log = LoggerFactory.getLogger(ApplicationCvPurgeWorker.class);

    private final ApplicationCvPurgeService purge;

    public ApplicationCvPurgeWorker(ApplicationCvPurgeService purge) {
        this.purge = purge;
    }

    @Scheduled(fixedDelayString = "${application.cv.purge.poll-ms:60000}",
            initialDelayString = "${application.cv.purge.poll-ms:60000}")
    public void purgeDue() {
        try {
            int purged = purge.purgeDue();
            if (purged > 0) {
                log.info("event=application_cv_purge outcome=completed purged={}", purged);
            }
        } catch (RuntimeException failure) {
            DiagnosticLog.error(log, DiagnosticEvent.UNEXPECTED_FAILURE, failure);
            log.warn("event=application_cv_purge outcome=retry_required");
        }
    }
}
