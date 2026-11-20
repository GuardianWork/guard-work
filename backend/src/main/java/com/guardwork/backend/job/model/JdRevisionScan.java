package com.guardwork.backend.job.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "jd_revision_scans")
public class JdRevisionScan {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  private Long companyId;
  private Long jobId;
  private Long revisionId;
  private Long revisionVersion;
  private Long scanRunId;
  private Long requestedBy;
  private Instant createdAt;
}
