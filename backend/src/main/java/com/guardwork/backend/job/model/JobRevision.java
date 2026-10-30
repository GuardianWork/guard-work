package com.guardwork.backend.job.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "job_posting_revisions")
public class JobRevision {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "job_id")
  private Long jobId;

  @Column(name = "revision_number")
  private int revisionNumber;

  @Column(name = "source_revision_id")
  private Long sourceRevisionId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "content", columnDefinition = "jsonb")
  private String content;

  @Column(name = "state")
  private String state;

  @Column(name = "creator_user_id")
  private Long creatorUserId;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "submitter_user_id")
  private Long submitterUserId;

  @Column(name = "submitted_at")
  private Instant submittedAt;

  @Column(name = "reviewer_user_id")
  private Long reviewerUserId;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "rejection_reason")
  private String rejectionReason;

  @Column(name = "withdrawer_user_id")
  private Long withdrawerUserId;

  @Column(name = "withdrawn_at")
  private Instant withdrawnAt;

  @Version
  @Column(name = "version")
  private Long version;

}
