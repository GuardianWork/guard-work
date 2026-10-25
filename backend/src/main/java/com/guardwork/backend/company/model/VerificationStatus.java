package com.guardwork.backend.company.model;

public enum VerificationStatus {
    PENDING,
    VERIFIED,
    REJECTED;

    public VerificationProcessingStatus processingStatus() {
        return this == PENDING ? VerificationProcessingStatus.PENDING : VerificationProcessingStatus.CHECKED;
    }

    public VerificationStatus reviewOutcome() {
        return this == PENDING ? null : this;
    }
}
