package com.guardwork.backend.notification.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

/**
 * Immutable fact-time context stored as {@code notification_outbox.payload}.
 *
 * <p>The outbox no longer keeps one column per context field; this document is frozen with the
 * rest of the event identity so the dispatcher can render copy without joining business tables.
 *
 * <p><strong>Extensible by design.</strong> The database column is plain {@code jsonb}, so a new
 * event or a new context field is an additive code change: add a record component and a factory
 * argument, and no migration is needed. Payloads are append-only snapshots and are never
 * rewritten, so:
 * <ul>
 *   <li>older rows simply have the new key missing and deserialize it as {@code null};
 *   <li>readers ignore keys they do not know, so old deployed code can read newer payloads;
 *   <li>unknown keys are dropped only if a payload were re-serialized, which never happens.
 * </ul>
 * Missing keys are therefore equivalent to {@code null}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationPayload(
    Long companyId,
    String companyName,
    Long jobId,
    String jobTitle,
    Long revisionId,
    UUID applicationId,
    String candidateFeedback,
    Long actorUserId,
    String actorName) {

  private static final ObjectMapper JSON =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private static final NotificationPayload EMPTY =
      new NotificationPayload(null, null, null, null, null, null, null, null, null);

  /** Membership events only need the owning Company. */
  public static NotificationPayload membership(Long companyId, String companyName) {
    return new NotificationPayload(companyId, companyName, null, null, null, null, null, null, null);
  }

  /** Job events carry the Job and, when known, its revision; their copy does not need a title. */
  public static NotificationPayload job(
      Long companyId, String companyName, Long jobId, Long revisionId) {
    return new NotificationPayload(
        companyId, companyName, jobId, null, revisionId, null, null, null, null);
  }

  /** Application events carry the applied-for role title so copy can name the position. */
  public static NotificationPayload application(
      Long companyId,
      String companyName,
      Long jobId,
      String jobTitle,
      UUID applicationId,
      String candidateFeedback) {
    return new NotificationPayload(
        companyId,
        companyName,
        jobId,
        jobTitle,
        null,
        applicationId,
        candidateFeedback,
        null,
        null);
  }

  public String toJson() {
    try {
      return JSON.writeValueAsString(this);
    } catch (Exception failure) {
      throw new IllegalStateException("Notification payload cannot be serialized", failure);
    }
  }

  public static NotificationPayload fromJson(String json) {
    if (json == null || json.isBlank()) {
      return EMPTY;
    }
    try {
      return JSON.readValue(json, NotificationPayload.class);
    } catch (Exception failure) {
      throw new IllegalStateException("Notification payload is unreadable", failure);
    }
  }
}
