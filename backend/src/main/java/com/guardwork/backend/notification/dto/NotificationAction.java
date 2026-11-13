package com.guardwork.backend.notification.dto;

import lombok.Builder;

/** Navigation destination only; the frontend renders its own label. */
@Builder
public record NotificationAction(String href) {}
