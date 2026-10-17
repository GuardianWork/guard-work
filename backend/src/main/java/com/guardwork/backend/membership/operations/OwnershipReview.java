package com.guardwork.backend.membership.operations;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "company_ownership_reviews")
@Getter
@Setter
@NoArgsConstructor
public class OwnershipReview {
  @Id
  @Column(name = "case_reference")
  private String caseReference;

  @Column(name = "company_id", nullable = false)
  private Long companyId;

  @Column(name = "applicant_user_id", nullable = false)
  private Long applicantUserId;

  @Column(name = "reviewer_user_id", nullable = false)
  private Long reviewerUserId;

  @Column(name = "evidence_type", nullable = false)
  private String evidenceType;

  @Column(nullable = false)
  private String decision;

  @Column(name = "company_version", nullable = false)
  private Long companyVersion;

  @Column(name = "verification_status", nullable = false)
  private String verificationStatus;

  @Column(name = "company_banned", nullable = false)
  private boolean companyBanned;

  @Column(name = "company_updated_at", nullable = false)
  private Instant companyUpdatedAt;

  @Column(name = "evidence_valid_until")
  private Instant evidenceValidUntil;

  @Column(name = "reviewed_at", nullable = false)
  private Instant reviewedAt;
}
