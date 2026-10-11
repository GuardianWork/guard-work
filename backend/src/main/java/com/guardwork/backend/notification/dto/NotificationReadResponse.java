package com.guardwork.backend.notification.dto;

import java.time.Instant;

public record NotificationReadResponse(Long id, Instant readAt) {}
