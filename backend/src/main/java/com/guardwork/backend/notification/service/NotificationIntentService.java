package com.guardwork.backend.notification.service;

import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.notification.model.NotificationOutbox;
import com.guardwork.backend.notification.repository.NotificationOutboxRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Record minimal notification intent in the same transaction as its business event. */
@Service
public class NotificationIntentService {
  private final NotificationOutboxRepository outbox;

  public NotificationIntentService(NotificationOutboxRepository outbox) {
    this.outbox = outbox;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID record(Long recipient, String eventType, Company company, Instant eventTime) {
    if (!"RECRUITER_ACCEPTED".equals(eventType) && !"MEMBERSHIP_REMOVED".equals(eventType))
      throw new IllegalArgumentException("Unsupported membership notification");
    var intent = new NotificationOutbox();
    intent.setEventId(UUID.randomUUID());
    intent.setRecipientUserId(recipient);
    intent.setEventType(eventType);
    intent.setCompanyId(company.getId());
    intent.setCompanyName(company.getName());
    intent.setCreatedAt(eventTime);
    intent.setNextAttemptAt(eventTime);
    outbox.saveAndFlush(intent);
    return intent.getEventId();
  }
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID recordJobAssignment(Long recipient, Company company, Long jobId, Long revisionId, Instant eventTime) {
    var intent = new NotificationOutbox();
    intent.setEventId(UUID.randomUUID());
    intent.setRecipientUserId(recipient);
    intent.setEventType("JOB_ASSIGNED");
    intent.setCompanyId(company.getId());
    intent.setCompanyName(company.getName());
    intent.setJobId(jobId);
    intent.setRevisionId(revisionId);
    intent.setCreatedAt(eventTime);
    intent.setNextAttemptAt(eventTime);
    outbox.saveAndFlush(intent);
    return intent.getEventId();
  }
}
