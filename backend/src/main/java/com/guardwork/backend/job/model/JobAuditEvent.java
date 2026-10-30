package com.guardwork.backend.job.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Immutable
@Entity
@Table(name = "job_audit_events")
public class JobAuditEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "company_id")
  private Long companyId;

  @Column(name = "job_id")
  private Long jobId;

  @Column(name = "revision_id")
  private Long revisionId;

  @Column(name = "membership_id")
  private Long membershipId;

  @Column(name = "actor_user_id")
  private Long actorUserId;

  @Column(name = "event_type")
  private String eventType;

  @Column(name = "created_at")
  private Instant createdAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "facts", columnDefinition = "jsonb")
  private Map<String, Object> facts;

}
