package com.guardwork.backend.notification.model;

/**
 * Role context a notification was recorded and delivered under. One {@code User} can be
 * both a Candidate and a company member, so the inbox is partitioned by audience;
 * persistence, scoping, and wire values share this enum.
 */
public enum NotificationAudience {
  CANDIDATE,
  RECRUITER;

  public String wireValue() {
    return name();
  }

  /** Case-insensitive parse; {@code null} when the wire value is unknown. */
  public static NotificationAudience fromWire(String wire) {
    if (wire != null) {
      for (NotificationAudience audience : values()) {
        if (audience.name().equalsIgnoreCase(wire.trim())) return audience;
      }
    }
    return null;
  }
}
