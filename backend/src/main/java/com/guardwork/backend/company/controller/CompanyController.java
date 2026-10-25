package com.guardwork.backend.company.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.guardwork.backend.auth.security.UserPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.guardwork.backend.staff.dto.CompanyVerificationResponse;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.company.dto.CompanyRegistrationRequest;
import com.guardwork.backend.company.dto.CurrentCompanyResponse;
import com.guardwork.backend.company.dto.OwnerVerificationResponse;
import com.guardwork.backend.company.dto.CompanyResubmissionRequest;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.service.CompanyService;
import com.guardwork.backend.onboarding.service.LegalDossierService;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    private final CompanyService companyService;
    private final LegalDossierService dossiers;

    public CompanyController(CompanyService companyService, LegalDossierService dossiers) {
        this.companyService = companyService;
        this.dossiers = dossiers;
    }

    @PostMapping("/verification-request")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CompanyVerificationResponse> requestVerification(@AuthenticationPrincipal UserPrincipal principal, @RequestBody CompanyRegistrationRequest request) {
        Company company = dossiers.register(principal.getId(), request);
        return ApiResponse.ok("Company submitted for verification", CompanyVerificationResponse.from(company));
    }

    @PutMapping("/{id}/resubmit")
    public ApiResponse<OwnerVerificationResponse> resubmitVerification(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long id,
            @RequestBody CompanyResubmissionRequest request
    ) {
        Company company = dossiers.correct(principal.getId(), id, request);
        return ApiResponse.ok("Company resubmitted for verification successfully", OwnerVerificationResponse.from(company));
    }

    @PutMapping("/me/verification")
    public ApiResponse<OwnerVerificationResponse> resubmitCurrentVerification(
            @AuthenticationPrincipal UserPrincipal principal, @RequestBody CompanyResubmissionRequest request) {
        return ApiResponse.ok("Company resubmitted for verification successfully",
                OwnerVerificationResponse.from(dossiers.correct(principal.getId(), null, request)));
    }

    @GetMapping("/me")
    public ApiResponse<CurrentCompanyResponse> getCurrentCompany(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok("Current Company retrieved successfully", companyService.getCurrentCompany(principal.getId()));
    }

    @GetMapping("/me/verification")
    public ApiResponse<OwnerVerificationResponse> getOwnerVerification(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok("Company verification retrieved successfully",
                OwnerVerificationResponse.from(companyService.getOwnerVerification(principal.getId())));
    }

    @GetMapping("/{id}")
    public ApiResponse<CompanyVerificationResponse> getCompany(@AuthenticationPrincipal UserPrincipal principal, @PathVariable("id") Long id) {
        Company company = companyService.getCompany(principal.getId(), id);
        return ApiResponse.ok("Company retrieved successfully", CompanyVerificationResponse.from(company));
    }

    @GetMapping("/tax-code/{taxCode}")
    public ApiResponse<CompanyVerificationResponse> getCompanyByTaxCode(@AuthenticationPrincipal UserPrincipal principal, @PathVariable("taxCode") String taxCode) {
        Company company = companyService.getCompanyByTaxCode(principal.getId(), taxCode);
        return ApiResponse.ok("Company retrieved successfully", CompanyVerificationResponse.from(company));
    }
}
