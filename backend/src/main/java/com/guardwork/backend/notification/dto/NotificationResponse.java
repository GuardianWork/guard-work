package com.guardwork.backend.notification.dto;

import com.guardwork.backend.notification.model.UserNotification;
import java.time.Instant;

public record NotificationResponse(
    Long id,
    String eventType,
    Long companyId,
    String companyName,
    Instant createdAt,
    Instant readAt,
    Long jobId,
    Long revisionId) {
  public static NotificationResponse from(UserNotification n) {
    return new NotificationResponse(
        n.getId(),
        n.getEventType(),
        n.getCompanyId(),
        n.getCompanyName(),
        n.getCreatedAt(),
        n.getReadAt(),
        n.getJobId(),
        n.getRevisionId());
  }
}
