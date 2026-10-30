package com.guardwork.backend.job.dto;

import com.guardwork.backend.job.model.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record JobDetail(
    Long id,
    Long companyId,
    Long creatorUserId,
    String status,
    Long version,
    boolean ownerResponsible,
    boolean needsReassignment,
    Revision workingRevision,
    Long publishedRevisionId,
    Instant approvedDeadline,
    List<Assignment> assignments,
    List<String> availableActions,
    Instant createdAt,
    Instant updatedAt) {
  public record Revision(
      Long id, int revisionNumber, String state, Long version, Map<String, Object> content) {
    public static Revision from(JobRevision r, Map<String, Object> content) {
      return r == null
          ? null
          : new Revision(r.getId(), r.getRevisionNumber(), r.getState(), r.getVersion(), content);
    }
  }

  public record Assignment(
      Long id, Long membershipId, Instant membershipJoinedAt, Instant assignedAt) {
    public static Assignment from(JobAssignment a) {
      return new Assignment(
          a.getId(), a.getMembershipId(), a.getMembershipJoinedAt(), a.getAssignedAt());
    }
  }
}
