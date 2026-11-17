package com.guardwork.backend.company.search.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.guardwork.backend.company.model.CompanyContent;

/**
 * Public company information used by the candidate-facing company dossier. {@code profile} is
 * the recruiter-authored, self-declared payload (schemaVersion 1); it is omitted entirely when
 * the company has not published one, so clients keep their "not disclosed" placeholders.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompanyDossierResponse(Long id, String name, CompanySummary summary, CompanyContent profile) {

    /** Profile is null when the company has not published content; omitting it keeps the contract quiet. */
    public static CompanyDossierResponse of(Long id, String name, CompanySummary summary, CompanyContent content) {
        CompanyContent profile = content != null && !content.payloadEmpty() ? content.stamped() : null;
        return new CompanyDossierResponse(id, name, summary, profile);
    }
}
