package com.guardwork.backend.membership.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "company_membership_audit_events")
public class MembershipAuditEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "actor_user_id", nullable = false)
  private Long actorUserId;

  @Column(name = "subject_user_id")
  private Long subjectUserId;

  @Column(name = "company_id", nullable = false)
  private Long companyId;

  @Column(name = "event_type", nullable = false, length = 100)
  private String eventType;

  @Column(name = "membership_id")
  private Long membershipId;

  @Column(name = "invitation_id")
  private Long invitationId;

  @Column(name = "review_case_reference")
  private String reviewCaseReference;

  @Column(name = "reviewer_user_id")
  private Long reviewerUserId;

  @Column(name = "evidence_type")
  private String evidenceType;

  @Column(name = "review_decision")
  private String reviewDecision;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  @Column(name = "assignment_outcome")
  private String assignmentOutcome;

  @Column(columnDefinition = "TEXT")
  private String reason;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = Instant.now();
  }
}
