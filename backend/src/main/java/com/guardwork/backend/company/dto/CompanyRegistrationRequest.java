package com.guardwork.backend.company.dto;

public record CompanyRegistrationRequest(
        String name,
        String taxCode,
        String registrationCertificateUrl
) {
}
