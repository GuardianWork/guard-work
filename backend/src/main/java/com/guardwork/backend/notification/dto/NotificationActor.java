package com.guardwork.backend.notification.dto;

import lombok.Builder;

/** Who caused the notification, denormalized so history survives account renames. */
@Builder
public record NotificationActor(String id, String name) {}
