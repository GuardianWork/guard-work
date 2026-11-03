package com.guardwork.backend.audit.dto;

import java.time.Instant;
import java.util.List;

public record ActivityItem(long id, Instant occurredAt, String category, String action,
    String targetType, String targetId, String revisionId, List<StateChange> changes, String reason) {
  public record StateChange(String field, Object before, Object after) {}
}
