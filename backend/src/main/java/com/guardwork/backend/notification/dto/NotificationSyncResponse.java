package com.guardwork.backend.notification.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

/**
 * One polling round of the inbox: the rows newer than the client watermark plus
 * the counters the badge and the tab badges need.
 *
 * <p>The delta carries no {@code read} filter, so a client detects a new
 * notification on every tab. {@code latestId} is the watermark the client sends
 * back as {@code sinceId}; {@code hasMore} tells the client that the delta was
 * truncated and that it has to reload the view.
 */
@Builder
public record NotificationSyncResponse(
    List<NotificationResponse> items,
    Long unreadCount,
    Long totalCount,
    Long latestId,
    Boolean hasMore,
    Instant serverTime) {}
