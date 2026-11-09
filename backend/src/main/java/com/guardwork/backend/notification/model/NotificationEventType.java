package com.guardwork.backend.notification.model;

/**
 * Concrete type of a notification event; wire values match the values persisted in
 * {@code event_type} columns and returned as the response {@code type}.
 */
public enum NotificationEventType {
  RECRUITER_ACCEPTED,
  MEMBERSHIP_REMOVED,
  JOB_ASSIGNED,
  JOB_UNASSIGNED,
  JOB_NEEDS_REASSIGNMENT,
  JOB_REVISION_APPROVED,
  JOB_REVISION_REJECTED,
  APPLICATION_STATUS_CHANGED,
  APPLICATION_REJECTED,
  APPLICATION_WITHDRAWN,
  UNKNOWN;

  public String wireValue() {
    return name();
  }

  /** Unknown wire values fall back to {@link #UNKNOWN} so legacy rows stay readable. */
  public static NotificationEventType fromWire(String wire) {
    if (wire != null) {
      for (NotificationEventType t : values()) {
        if (t.name().equals(wire)) return t;
      }
    }
    return UNKNOWN;
  }
}
