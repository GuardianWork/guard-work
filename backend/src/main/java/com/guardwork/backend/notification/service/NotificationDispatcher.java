package com.guardwork.backend.notification.service;

import com.guardwork.backend.notification.model.*;
import com.guardwork.backend.notification.repository.*;
import java.time.Clock;
import java.util.UUID;
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
public class NotificationDispatcher {
  private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatcher.class);
  private final NotificationOutboxRepository outbox;
  private final UserNotificationRepository inbox;
  private final Clock clock;
  private final TransactionTemplate transaction;

  public NotificationDispatcher(
      NotificationOutboxRepository outbox,
      UserNotificationRepository inbox,
      Clock clock,
      PlatformTransactionManager manager) {
    this.outbox = outbox;
    this.inbox = inbox;
    this.clock = clock;
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public void dispatch(UUID eventId) {
    try {
      transaction.executeWithoutResult(
          tx -> {
            var event = outbox.findForUpdate(eventId).orElse(null);
            if (event == null
                || event.getDeliveredAt() != null
                || event.getNextAttemptAt().isAfter(clock.instant())) return;
            if (!inbox.existsBySourceEventId(eventId)) {
              var item = new UserNotification();
              item.setSourceEventId(eventId);
              item.setRecipientUserId(event.getRecipientUserId());
              item.setEventType(event.getEventType());
              item.setCompanyId(event.getCompanyId());
              item.setCompanyName(event.getCompanyName());
              item.setCreatedAt(event.getCreatedAt());
              item.setJobId(event.getJobId());
              item.setRevisionId(event.getRevisionId());
              inbox.saveAndFlush(item);
            }
            event.setDeliveredAt(clock.instant());
            event.setErrorCategory(null);
          });
    } catch (RuntimeException failure) {
      try {
        transaction.executeWithoutResult(
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
    transaction.executeWithoutResult(
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
