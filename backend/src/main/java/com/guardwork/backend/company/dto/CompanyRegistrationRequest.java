package com.guardwork.backend.company.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CompanyRegistrationRequest(
        @NotBlank String name,
        @NotBlank String taxCode,
        @NotBlank @Email(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") String email,
        @NotBlank String registrationCertificateUrl,
        LegalDetails legal
) {
    public CompanyRegistrationRequest(String name, String taxCode, String email, String registrationCertificateUrl) {
        this(name, taxCode, email, registrationCertificateUrl, null);
    }
    public CompanyRegistrationRequest(String name, String taxCode, String registrationCertificateUrl) {
        this(name, taxCode, null, registrationCertificateUrl);
    }
}
