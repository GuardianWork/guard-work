package com.guardwork.backend.application.dto;

import java.time.Instant;

public record ApplicationStatusHistoryResponse(
        String fromStatus,
        String toStatus,
        Long actorUserId,
        String actorName,
        Instant occurredAt,
        String rejectionReason,
        String internalExplanation,
        String candidateFeedback) {
}
