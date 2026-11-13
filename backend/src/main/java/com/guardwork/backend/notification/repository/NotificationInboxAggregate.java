package com.guardwork.backend.notification.repository;

/**
 * Recipient-scoped counters of the inbox, computed in one pass.
 *
 * <p>{@code latestId} is the highest inbox id the recipient owns; it is the
 * watermark a client sends back as {@code sinceId}. {@code total} counts every
 * row of the recipient and {@code unread} the rows without a read receipt.
 */
public interface NotificationInboxAggregate {
  Long getLatestId();

  Long getTotal();

  Long getUnread();
}
