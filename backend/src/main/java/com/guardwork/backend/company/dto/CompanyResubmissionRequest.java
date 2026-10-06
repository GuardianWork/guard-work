package com.guardwork.backend.company.dto;

public record CompanyResubmissionRequest(
        String name,
        String registrationCertificateUrl,
        Long expectedVersion
) {
}
