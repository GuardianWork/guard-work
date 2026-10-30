package com.guardwork.backend.company.dto;

public record CompanyDossierRequest(String name, String taxCode, Legal legal) {
    public record Legal(String workAddress, String submitterPosition, String representation,
                        Boolean declarationAccepted) {}
}
