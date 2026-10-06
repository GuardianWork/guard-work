package com.guardwork.backend.company.dto;

public record CompanyResubmissionRequest(
        String name,
        String email,
        String registrationCertificateUrl,
        Long expectedVersion
) {
    public CompanyResubmissionRequest(String name, String registrationCertificateUrl, Long expectedVersion) {
        this(name, null, registrationCertificateUrl, expectedVersion);
    }
}
