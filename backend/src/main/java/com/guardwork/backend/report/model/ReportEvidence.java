package com.guardwork.backend.report.model;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_report_evidence")
public class ReportEvidence {
  @Id private UUID id;
  @Column(name = "report_id", nullable = false) private Long reportId;
  @Column(nullable = false) private String filename;
  @Column(name = "content_type", nullable = false) private String contentType;
  @Column(name = "size_bytes", nullable = false) private long sizeBytes;
  @Column(name = "content_hash", nullable = false) private String contentHash;
  @Column(columnDefinition = "bytea") private byte[] content;
}
