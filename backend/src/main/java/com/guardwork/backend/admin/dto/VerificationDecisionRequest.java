package com.guardwork.backend.admin.dto;

import com.guardwork.backend.company.model.VerificationStatus;

public record VerificationDecisionRequest(
        VerificationStatus status,
        String rejectionReason,
        Long expectedVersion
) {
    public VerificationDecisionRequest(String rejectionReason, Long expectedVersion) {
        this(VerificationStatus.REJECTED, rejectionReason, expectedVersion);
    }
}
