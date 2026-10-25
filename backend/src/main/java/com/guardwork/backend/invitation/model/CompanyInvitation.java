package com.guardwork.backend.invitation.model;

import com.guardwork.backend.membership.model.CompanyRole;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "company_invitations")
public class CompanyInvitation {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "company_id", nullable = false)
  private Long companyId;

  @Column(name = "recipient_email_normalized", nullable = false, length = 255)
  private String recipientEmailNormalized;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private CompanyRole role = CompanyRole.RECRUITER;

  @Column(name = "invited_by_user_id", nullable = false)
  private Long invitedByUserId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private InvitationStatus status = InvitationStatus.PENDING;

  @Column(name = "secret_hash", nullable = false, length = 64)
  private String secretHash;

  @Column(name = "issued_at", nullable = false)
  private Instant issuedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "accepted_membership_id")
  private Long acceptedMembershipId;

  @Column(name = "decided_by_user_id")
  private Long decidedByUserId;

  @Column(name = "accepted_by_user_id")
  private Long acceptedByUserId;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "delivery_status", nullable = false, length = 30)
  private DeliveryStatus deliveryStatus = DeliveryStatus.UNKNOWN;

  @Column(name = "last_delivery_attempt_at")
  private Instant lastDeliveryAttemptAt;
}
