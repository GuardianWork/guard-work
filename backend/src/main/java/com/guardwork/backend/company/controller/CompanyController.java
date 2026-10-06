package com.guardwork.backend.company.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.guardwork.backend.admin.dto.CompanyVerificationResponse;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.company.dto.CompanyRegistrationRequest;
import com.guardwork.backend.company.dto.CompanyResubmissionRequest;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.service.CompanyService;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @PostMapping("/verification-request")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CompanyVerificationResponse> requestVerification(@RequestBody CompanyRegistrationRequest request) {
        Company company = companyService.registerCompany(request);
        return ApiResponse.ok("Company submitted for verification", CompanyVerificationResponse.from(company));
    }

    @PutMapping("/{id}/resubmit")
    public ApiResponse<CompanyVerificationResponse> resubmitVerification(
            @PathVariable("id") Long id,
            @RequestBody CompanyResubmissionRequest request
    ) {
        Company company = companyService.resubmitCompany(id, request);
        return ApiResponse.ok("Company resubmitted for verification successfully", CompanyVerificationResponse.from(company));
    }

    @GetMapping("/{id}")
    public ApiResponse<CompanyVerificationResponse> getCompany(@PathVariable("id") Long id) {
        Company company = companyService.getCompany(id);
        return ApiResponse.ok("Company retrieved successfully", CompanyVerificationResponse.from(company));
    }
}
