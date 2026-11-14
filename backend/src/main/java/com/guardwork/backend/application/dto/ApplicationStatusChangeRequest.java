package com.guardwork.backend.application.dto;

public record ApplicationStatusChangeRequest(
        String status,
        Long version,
        String rejectionReason,
        String internalExplanation,
        String candidateFeedback,
        Boolean hiringConfirmed) {
}
