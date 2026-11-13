package com.guardwork.backend.notification.dto;

import java.time.Instant;
import lombok.Builder;

/** Result of marking every unread inbox row read in one transaction. */
@Builder
public record NotificationMarkAllReadResponse(Long updatedCount, Instant readAt) {}
