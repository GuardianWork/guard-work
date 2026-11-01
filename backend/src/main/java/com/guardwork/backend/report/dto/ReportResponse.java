package com.guardwork.backend.report.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReportResponse {
  private ReportResponse() {}
  public record Receipt(Long id, String targetType, String targetId, String status, Instant createdAt) {}
  public record Summary(Long id, String targetType, String targetId, String status, Instant createdAt,
      String complaint, String targetLabel, boolean targetAvailable) {}
  public record Reporter(Long userId, String displayName) {}
  public record Evidence(UUID id, String filename, String contentType, long sizeBytes,
      boolean available, String downloadPath) {}
  public record Detail(Long id, String targetType, String targetId, String status, Instant createdAt,
      String complaint, String targetLabel, boolean targetAvailable, Reporter reporter,
      String processingOutcome, List<Evidence> evidence) {}
}
