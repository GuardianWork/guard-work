package com.guardwork.backend.company.dto;

public record CurrentCompanyResponse(Long companyId, String companyName, String companyRole, boolean companySuspended) {
    public CurrentCompanyResponse(Long companyId, String companyName, String companyRole) {
        this(companyId, companyName, companyRole, false);
    }
}
