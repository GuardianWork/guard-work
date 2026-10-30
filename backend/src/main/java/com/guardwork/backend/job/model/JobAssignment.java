package com.guardwork.backend.job.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "job_assignments")
public class JobAssignment {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "job_id")
  private Long jobId;

  @Column(name = "company_id")
  private Long companyId;

  @Column(name = "membership_id")
  private Long membershipId;

  @Column(name = "membership_joined_at")
  private Instant membershipJoinedAt;

  @Column(name = "assigned_by_user_id")
  private Long assignedByUserId;

  @Column(name = "assigned_at")
  private Instant assignedAt;

  @Column(name = "ended_at")
  private Instant endedAt;

  @Column(name = "ended_by_user_id")
  private Long endedByUserId;

  @Column(name = "end_reason")
  private String endReason;

}
