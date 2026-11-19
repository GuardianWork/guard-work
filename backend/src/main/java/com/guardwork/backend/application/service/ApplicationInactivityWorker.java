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
@ConditionalOnProperty(name = "application.review.inactivity-worker-enabled",
        havingValue = "true", matchIfMissing = true)
public class ApplicationInactivityWorker {

    private static final Logger log = LoggerFactory.getLogger(ApplicationInactivityWorker.class);

    private final ApplicationInactivityService inactivity;

    public ApplicationInactivityWorker(ApplicationInactivityService inactivity) {
        this.inactivity = inactivity;
    }

    @Scheduled(fixedDelayString = "${application.review.inactivity-poll-ms:60000}",
            initialDelayString = "${application.review.inactivity-poll-ms:60000}")
    public void expireDue() {
        try {
            int changed = inactivity.expireDue();
            if (changed > 0) {
                log.info("event=application_inactive outcome=completed changed={}", changed);
            }
        } catch (RuntimeException failure) {
            DiagnosticLog.error(log, DiagnosticEvent.UNEXPECTED_FAILURE, failure);
            log.warn("event=application_inactive outcome=retry_required");
        }
    }
}
