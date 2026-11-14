package com.guardwork.backend.job.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "jd_scan_attempts")
public class JdScanAttempt {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  private Long scanRunId;
  private UUID claimToken;
  private int attemptNumber;
  private Instant startedAt;
  private Instant finishedAt;
  private String outcome;
  private Long durationMs;
}
