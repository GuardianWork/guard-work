package com.guardwork.backend.report.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_reports")
public class UserReport {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "reporter_id", nullable = false)
  private Long reporterId;
  @Column(name = "target_type", nullable = false)
  private String targetType;
  @Column(name = "target_id", nullable = false)
  private String targetId;
  @Column(nullable = false, columnDefinition = "TEXT")
  private String complaint;
  @Column(nullable = false)
  private String status;
  @Column(name = "processing_outcome", columnDefinition = "TEXT")
  private String processingOutcome;
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
