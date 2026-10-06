package com.guardwork.backend.admin.dto;

import java.time.Instant;

import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.VerificationStatus;

public record CompanyVerificationResponse(
        Long id,
        String name,
        String taxCode,
        String registrationCertificateUrl,
        VerificationStatus verificationStatus,
        String rejectionReason,
        Long verifiedBy,
        Instant verifiedAt,
        Long version,
        Instant createdAt
) {
    public static CompanyVerificationResponse from(Company company) {
        return new CompanyVerificationResponse(
                company.getId(),
                company.getName(),
                company.getTaxCode(),
                company.getRegistrationCertificateUrl(),
                company.getVerificationStatus(),
                company.getRejectionReason(),
                company.getVerifiedBy(),
                company.getVerifiedAt(),
                company.getVersion(),
                company.getCreatedAt()
        );
    }
}
