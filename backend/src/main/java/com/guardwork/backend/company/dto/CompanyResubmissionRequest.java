package com.guardwork.backend.company.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

public record CompanyResubmissionRequest(
        String name,
        @Email(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") String email,
        String registrationCertificateUrl,
        @NotNull Long expectedVersion,
        LegalDetails legal
) {
    public CompanyResubmissionRequest(String name, String email, String registrationCertificateUrl, Long expectedVersion) {
        this(name, email, registrationCertificateUrl, expectedVersion, null);
    }
    public CompanyResubmissionRequest(String name, String registrationCertificateUrl, Long expectedVersion) {
        this(name, null, registrationCertificateUrl, expectedVersion);
    }
}
