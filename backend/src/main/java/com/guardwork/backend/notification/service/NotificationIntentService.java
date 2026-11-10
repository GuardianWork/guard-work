package com.guardwork.backend.notification.service;

import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.notification.model.NotificationAudience;
import com.guardwork.backend.notification.model.NotificationEventType;
import com.guardwork.backend.notification.model.NotificationOutbox;
import com.guardwork.backend.notification.model.NotificationPayload;
import com.guardwork.backend.notification.repository.NotificationOutboxRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Record minimal notification intent in the same transaction as its business event. */
@Service
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class NotificationIntentService {
  NotificationOutboxRepository outbox;

  /** Membership events are employer-facing. */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID record(
      Long recipient,
      String eventType,
      NotificationAudience audience,
      Company company,
      Instant eventTime) {
    return record(recipient, NotificationEventType.fromWire(eventType), audience, company, eventTime);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID record(
      Long recipient,
      NotificationEventType eventType,
      NotificationAudience audience,
      Company company,
      Instant eventTime) {
    if (!Set.of(NotificationEventType.RECRUITER_ACCEPTED, NotificationEventType.MEMBERSHIP_REMOVED)
        .contains(eventType))
      throw new IllegalArgumentException("Unsupported membership notification");
    return persist(
        recipient,
        eventType,
        audience,
        NotificationPayload.membership(company.getId(), company.getName()),
        eventTime);
  }

  /** Job events are employer-facing. */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID recordJobAssignment(
      Long recipient, Company company, Long jobId, Long revisionId, Instant eventTime) {
    return recordJob(
        recipient,
        NotificationEventType.JOB_ASSIGNED,
        NotificationAudience.RECRUITER,
        company,
        jobId,
        revisionId,
        eventTime);
  }

  /** Legacy string entry point; converts then resolves by kind. */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID recordJob(
      Long recipient,
      String eventType,
      NotificationAudience audience,
      Company company,
      Long jobId,
      Long revisionId,
      Instant eventTime) {
    return recordJob(
        recipient,
        NotificationEventType.fromWire(eventType),
        audience,
        company,
        jobId,
        revisionId,
        eventTime);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID recordJob(
      Long recipient,
      NotificationEventType eventType,
      NotificationAudience audience,
      Company company,
      Long jobId,
      Long revisionId,
      Instant eventTime) {
    if (!Set.of(
            NotificationEventType.JOB_ASSIGNED,
            NotificationEventType.JOB_UNASSIGNED,
            NotificationEventType.JOB_NEEDS_REASSIGNMENT,
            NotificationEventType.JOB_REVISION_APPROVED,
            NotificationEventType.JOB_REVISION_REJECTED)
        .contains(eventType))
      throw new IllegalArgumentException("Unsupported job notification");
    return persist(
        recipient,
        eventType,
        audience,
        NotificationPayload.job(company.getId(), company.getName(), jobId, revisionId),
        eventTime);
  }

  /** Application status/rejection notify the candidate; withdrawal notifies the employer. */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID recordApplication(
      Long recipient,
      String eventType,
      NotificationAudience audience,
      Company company,
      Long jobId,
      String jobTitle,
      UUID applicationId,
      String candidateFeedback,
      Instant eventTime) {
    return recordApplication(
        recipient,
        NotificationEventType.fromWire(eventType),
        audience,
        company,
        jobId,
        jobTitle,
        applicationId,
        candidateFeedback,
        eventTime);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID recordApplication(
      Long recipient,
      NotificationEventType eventType,
      NotificationAudience audience,
      Company company,
      Long jobId,
      String jobTitle,
      UUID applicationId,
      String candidateFeedback,
      Instant eventTime) {
    if (!Set.of(
            NotificationEventType.APPLICATION_STATUS_CHANGED,
            NotificationEventType.APPLICATION_REJECTED,
            NotificationEventType.APPLICATION_WITHDRAWN,
            NotificationEventType.APPLICATION_INTERVIEWING,
            NotificationEventType.APPLICATION_HIRED)
        .contains(eventType))
      throw new IllegalArgumentException("Unsupported application notification");
    return persist(
        recipient,
        eventType,
        audience,
        NotificationPayload.application(
            company.getId(), company.getName(), jobId, jobTitle, applicationId, candidateFeedback),
        eventTime);
  }

  private UUID persist(
      Long recipient,
      NotificationEventType eventType,
      NotificationAudience audience,
      NotificationPayload payload,
      Instant eventTime) {
    var intent =
        NotificationOutbox.builder()
            .eventId(UUID.randomUUID())
            .recipientUserId(recipient)
            .eventType(eventType)
            .audience(requireAudience(audience))
            .payload(payload.toJson())
            .createdAt(eventTime)
            .nextAttemptAt(eventTime)
            .build();
    outbox.saveAndFlush(intent);
    return intent.getEventId();
  }

  private static NotificationAudience requireAudience(NotificationAudience audience) {
    if (audience == null) throw new IllegalArgumentException("Notification audience is required");
    return audience;
  }
}
