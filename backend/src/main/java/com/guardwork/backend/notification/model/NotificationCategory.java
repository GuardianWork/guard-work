package com.guardwork.backend.notification.model;

/** Canonical notification categories; wire values match the frontend NOTIFICATION_CATEGORIES list. */
public enum NotificationCategory {
  JOB("job"),
  APPLICATION("application"),
  COMPANY("company"),
  ACCOUNT("account"),
  VERIFICATION("verification"),
  ANNOUNCEMENT("announcement"),
  COMMUNITY("community"),
  REWARD("reward"),
  PROMOTION("promotion"),
  SYSTEM("system");

  private final String wireValue;

  NotificationCategory(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  public static NotificationCategory fromWire(String wire) {
    for (NotificationCategory c : values()) {
      if (c.wireValue.equals(wire)) return c;
    }
    return SYSTEM;
  }
}
