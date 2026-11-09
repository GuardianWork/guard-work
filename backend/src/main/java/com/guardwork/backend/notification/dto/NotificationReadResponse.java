package com.guardwork.backend.notification.dto;

import java.time.Instant;
import lombok.Builder;

@Builder
public record NotificationReadResponse(Long id, Instant readAt) {}
