package com.guardwork.backend.job.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "job_postings")
public class JobPosting {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "company_id")
  private Long companyId;

  @Column(name = "creator_user_id")
  private Long creatorUserId;

  @Column(name = "status")
  private String status;

  @Column(name = "owner_responsible")
  private boolean ownerResponsible;

  @Column(name = "published_revision_id")
  private Long publishedRevisionId;

  @Column(name = "approved_deadline")
  private Instant approvedDeadline;

  @Column(name = "staff_suspended", nullable = false)
  private boolean staffSuspended;

  @Version
  @Column(name = "version")
  private Long version;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "expired_at")
  private Instant expiredAt;

}
