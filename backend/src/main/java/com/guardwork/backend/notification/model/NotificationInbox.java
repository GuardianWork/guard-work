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
@Table(name = "notification_inbox")
public class NotificationInbox {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "source_event_id", nullable = false)
  private UUID sourceEventId;

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

  @Column(name = "category", nullable = false)
  private String category;

  @Column(name = "title", length = 200)
  private String title;

  @Column(name = "body", columnDefinition = "TEXT")
  private String body;

  @Column(name = "action_href", length = 1024)
  private String actionHref;

  @Column(name = "actor_user_id")
  private Long actorUserId;

  @Column(name = "actor_name", length = 255)
  private String actorName;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "read_at")
  private Instant readAt;
}
