package com.guardwork.backend.company.dto;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.VerificationProcessingStatus;
import com.guardwork.backend.company.model.VerificationStatus;

public record OwnerVerificationResponse(Long companyId, String name, String taxCode, String email,
        String registrationCertificateUrl, VerificationStatus verificationStatus, String rejectionReason,
        Long version, Instant createdAt, Instant updatedAt, boolean companySuspended) {
    public OwnerVerificationResponse(Long companyId, String name, String taxCode, String email,
            String registrationCertificateUrl, VerificationStatus verificationStatus, String rejectionReason,
            Long version, Instant createdAt, Instant updatedAt) {
        this(companyId, name, taxCode, email, registrationCertificateUrl, verificationStatus, rejectionReason,
                version, createdAt, updatedAt, false);
    }
    @JsonProperty("processingStatus")
    public VerificationProcessingStatus processingStatus() {
        return verificationStatus.processingStatus();
    }

    @JsonProperty("reviewOutcome")
    public VerificationStatus reviewOutcome() {
        return verificationStatus.reviewOutcome();
    }

    public static OwnerVerificationResponse from(Company company) {
        return new OwnerVerificationResponse(company.getId(), company.getName(), company.getTaxCode(), company.getEmail(),
                company.getRegistrationCertificateUrl(), company.getVerificationStatus(), company.getRejectionReason(),
                company.getVersion(), company.getCreatedAt(), company.getUpdatedAt(), company.isBanned());
    }
}
