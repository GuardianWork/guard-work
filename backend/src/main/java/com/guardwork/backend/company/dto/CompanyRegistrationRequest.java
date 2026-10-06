package com.guardwork.backend.company.dto;

public record CompanyRegistrationRequest(
        String name,
        String taxCode,
        String email,
        String registrationCertificateUrl
) {
    public CompanyRegistrationRequest(String name, String taxCode, String registrationCertificateUrl) {
        this(name, taxCode, null, registrationCertificateUrl);
    }
}
