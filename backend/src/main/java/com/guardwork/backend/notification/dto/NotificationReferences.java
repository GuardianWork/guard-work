package com.guardwork.backend.notification.dto;

import java.util.UUID;
import lombok.Builder;

/**
 * Per-event identifiers the frontend needs to open the right screen.
 * Every field is nullable; features populate only what their event carries.
 */
@Builder
public record NotificationReferences(
    Long companyId,
    String companyName,
    Long jobId,
    Long revisionId,
    UUID applicationId,
    String candidateFeedback) {}
