package com.guardwork.backend.job.model;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "jd_scan_runs")
public class JdScanRun {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  private Long companyId;
  private String inputHash;
  @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private String inputContent;
  private String analysisText;
  private String profile;
  private String legalSnapshot;
  private String mode;
  private LocalDate asOfDate;
  private String status;
  @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private String report;
  private int attemptCount;
  private Instant availableAt;
  private UUID leaseToken;
  private Instant leaseUntil;
  private String failureCode;
  private Instant createdAt;
  private Instant finishedAt;
}
