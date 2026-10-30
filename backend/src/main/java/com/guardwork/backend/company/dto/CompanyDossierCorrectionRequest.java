package com.guardwork.backend.company.dto;

public record CompanyDossierCorrectionRequest(String name, Long expectedVersion, CompanyDossierRequest.Legal legal) {}
