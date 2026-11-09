package com.guardwork.backend.notification.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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

  @Column(name = "company_id", nullable = false)
  private Long companyId;

  @Column(name = "company_name", nullable = false)
  private String companyName;

  @Column(name = "job_id")
  private Long jobId;

  @Column(name = "revision_id")
  private Long revisionId;

  @Column(name = "application_id")
  private UUID applicationId;

  @Column(name = "candidate_feedback", length = 1000)
  private String candidateFeedback;

  @Column(name = "actor_user_id")
  private Long actorUserId;

  @Column(name = "actor_name", length = 255)
  private String actorName;

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
}
