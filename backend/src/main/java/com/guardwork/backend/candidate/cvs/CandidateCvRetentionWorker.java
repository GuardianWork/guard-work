package com.guardwork.backend.candidate.cvs;

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
@ConditionalOnProperty(name = "candidate.cv.retention.worker-enabled",
        havingValue = "true", matchIfMissing = true)
public class CandidateCvRetentionWorker {

    private static final Logger log = LoggerFactory.getLogger(CandidateCvRetentionWorker.class);
    private static final int BATCH_SIZE = 20;

    private final CandidateCvRetentionService retention;

    public CandidateCvRetentionWorker(CandidateCvRetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${candidate.cv.retention.poll-ms:60000}",
            initialDelayString = "${candidate.cv.retention.poll-ms:60000}")
    public void purgeDue() {
        try {
            int purged = retention.purgeDue(BATCH_SIZE);
            if (purged > 0) {
                log.info("event=cv_object_purge outcome=completed purged={}", purged);
            }
        } catch (RuntimeException failure) {
            DiagnosticLog.error(log, DiagnosticEvent.UNEXPECTED_FAILURE, failure);
            log.warn("event=cv_object_purge outcome=retry_required");
        }
    }
}
