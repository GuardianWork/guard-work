package com.guardwork.backend.company.dto;

import java.time.Instant;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.VerificationStatus;

public record OwnerVerificationResponse(Long companyId, String name, String taxCode, String email,
        String registrationCertificateUrl, VerificationStatus verificationStatus, String rejectionReason,
        Long version, Instant createdAt, Instant updatedAt) {
    public static OwnerVerificationResponse from(Company company) {
        return new OwnerVerificationResponse(company.getId(), company.getName(), company.getTaxCode(), company.getEmail(),
                company.getRegistrationCertificateUrl(), company.getVerificationStatus(), company.getRejectionReason(),
                company.getVersion(), company.getCreatedAt(), company.getUpdatedAt());
    }
}
