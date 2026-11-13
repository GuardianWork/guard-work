package com.guardwork.backend.notification.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notification_outbox")
public class NotificationOutbox {
  @Id
  @Column(name = "event_id")
  private UUID eventId;

  @Column(name = "recipient_user_id", nullable = false)
  private Long recipientUserId;

  @Column(name = "event_type", nullable = false)
  @Enumerated(EnumType.STRING)
  private NotificationEventType eventType;

  @Column(name = "audience", nullable = false)
  @Enumerated(EnumType.STRING)
  private NotificationAudience audience;

  /** Frozen fact-time context; see {@link NotificationPayload}. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
  private String payload;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "delivered_at")
  private Instant deliveredAt;

  @Column(name = "error_category")
  private String errorCategory;

  /** Typed view of {@link #payload} for templates and the dispatcher. */
  public NotificationPayload payloadView() {
    return NotificationPayload.fromJson(payload);
  }
}
