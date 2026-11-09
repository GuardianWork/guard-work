package com.guardwork.backend.notification.dto;

import lombok.Builder;

/** Unread inbox total for the bell badge and tab counts. */
@Builder
public record NotificationUnreadCountResponse(Long unreadCount) {}
