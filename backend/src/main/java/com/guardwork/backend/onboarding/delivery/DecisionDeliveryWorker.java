package com.guardwork.backend.onboarding.delivery;

import com.guardwork.backend.onboarding.repository.DecisionDeliveryRepository;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "employer.onboarding.delivery-worker-enabled", havingValue = "true", matchIfMissing = true)
public class DecisionDeliveryWorker {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DecisionDeliveryWorker.class);
    private final DecisionDeliveryRepository deliveries;
    private final OnboardingDelivery sender;
    private final TransactionTemplate tx;

    public DecisionDeliveryWorker(DecisionDeliveryRepository deliveries, OnboardingDelivery sender, PlatformTransactionManager manager) {
        this.deliveries = deliveries; this.sender = sender; this.tx = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${employer.onboarding.delivery-poll-ms:30000}", initialDelayString = "${employer.onboarding.delivery-poll-ms:30000}")
    public void deliverDue() {
        for (var candidate : deliveries.findTop20ByDeliveredAtIsNullAndAttemptsLessThanAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(8, Instant.now())) {
            tx.executeWithoutResult(status -> {
                var d = deliveries.findLocked(candidate.getId()).orElseThrow();
                if (d.getDeliveredAt() != null || d.getAttempts() >= 8 || d.getNextAttemptAt().isAfter(Instant.now())) return;
                boolean accepted = sender.decisionEmail(d.getRecipientEmail(), d.getCompanyName(), d.getOutcome(), d.getRejectionReason(), d.getId());
                d.setAttempts(d.getAttempts() + 1);
                if (accepted) d.setDeliveredAt(Instant.now());
                else {
                    d.setNextAttemptAt(Instant.now().plusSeconds(Math.min(3600, 30L << d.getAttempts())));
                    if (d.getAttempts() >= 8) log.warn("event=company_decision_email outcome=retry_exhausted");
                    else log.warn("event=company_decision_email outcome=retry_scheduled");
                }
                deliveries.saveAndFlush(d);
            });
        }
    }
}
