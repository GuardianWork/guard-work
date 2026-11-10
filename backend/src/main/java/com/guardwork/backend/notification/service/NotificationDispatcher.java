package com.guardwork.backend.notification.service;

import com.guardwork.backend.notification.model.*;
import com.guardwork.backend.notification.repository.*;
import com.guardwork.backend.notification.template.NotificationTemplate;
import java.time.Clock;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Public operational recovery seam; every dispatch runs independently of the business transaction.
 */
@Service
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class NotificationDispatcher {
  private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatcher.class);
  NotificationOutboxRepository outbox;
  NotificationInboxRepository inbox;
  Clock clock;
  PlatformTransactionManager manager;

  private TransactionTemplate newTx() {
    TransactionTemplate t = new TransactionTemplate(manager);
    t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return t;
  }

  public void dispatch(UUID eventId) {
    try {
      newTx().executeWithoutResult(
          tx -> {
            var event = outbox.findForUpdate(eventId).orElse(null);
            if (event == null
                || event.getDeliveredAt() != null
                || event.getNextAttemptAt().isAfter(clock.instant())) return;
            if (!inbox.existsBySourceEventId(eventId)) {
              NotificationTemplate template = NotificationTemplate.find(event.getEventType());
              var payload = event.payloadView();
              var item =
                  NotificationInbox.builder()
                      .sourceEventId(eventId)
                      .recipientUserId(event.getRecipientUserId())
                      .eventType(event.getEventType())
                      .audience(event.getAudience())
                      .companyId(payload.companyId())
                      .companyName(payload.companyName())
                      .createdAt(event.getCreatedAt())
                      .jobId(payload.jobId())
                      .revisionId(payload.revisionId())
                      .applicationId(payload.applicationId())
                      .candidateFeedback(payload.candidateFeedback())
                      .category(template.categoryValue())
                      .title(template.title(event))
                      .body(template.body(event))
                      .actionHref(template.actionHref(event))
                      .actorUserId(payload.actorUserId())
                      .actorName(payload.actorName())
                      .build();
              inbox.saveAndFlush(item);
            }
            event.setDeliveredAt(clock.instant());
            event.setErrorCategory(null);
          });
    } catch (RuntimeException failure) {
      try {
        newTx().executeWithoutResult(
            tx -> {
              var event = outbox.findForUpdate(eventId).orElse(null);
              if (event == null || event.getDeliveredAt() != null) return;
              int attempt = event.getAttemptCount() + 1;
              event.setAttemptCount(attempt);
              long seconds = Math.min(1800, 30L << Math.min(6, attempt - 1));
              event.setNextAttemptAt(clock.instant().plusSeconds(seconds));
              event.setErrorCategory("INBOX_WRITE_FAILED");
              if (attempt >= 5)
                LOG.warn(
                    "Membership notification delivery requires recovery: event={} attempts={}"
                        + " category=INBOX_WRITE_FAILED",
                    eventId,
                    attempt);
            });
      } catch (RuntimeException unavailable) {
        // Original intent remains durable and eligible; never throw into a committed business
        // response.
        LOG.warn(
            "Membership notification retry metadata unavailable: event={}"
                + " category=RETRY_WRITE_FAILED",
            eventId);
      }
    }
  }

  public void dispatchDue() {
    try {
      for (UUID id : outbox.findDue(clock.instant(), PageRequest.of(0, 100))) dispatch(id);
    } catch (RuntimeException unavailable) {
      LOG.warn("Membership notification scan unavailable: category=OUTBOX_READ_FAILED");
    }
  }

  /** Trusted operator replay; no public HTTP endpoint. Retains failure history. */
  public void replay(UUID eventId) {
    newTx().executeWithoutResult(
        tx ->
            outbox
                .findForUpdate(eventId)
                .ifPresent(
                    event -> {
                      if (event.getDeliveredAt() == null) event.setNextAttemptAt(clock.instant());
                    }));
    dispatch(eventId);
  }
}
