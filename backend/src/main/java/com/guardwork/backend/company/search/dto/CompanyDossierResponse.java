package com.guardwork.backend.company.search.dto;

/** Public company information used by the candidate-facing company dossier. */
public record CompanyDossierResponse(Long id, String name, CompanySummary summary) {
}
