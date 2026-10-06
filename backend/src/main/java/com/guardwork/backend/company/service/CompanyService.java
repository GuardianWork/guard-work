package com.guardwork.backend.company.service;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.guardwork.backend.company.dto.CompanyRegistrationRequest;
import com.guardwork.backend.company.dto.CompanyResubmissionRequest;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.VerificationStatus;
import com.guardwork.backend.company.repository.CompanyRepository;

@Service
public class CompanyService {

    private final CompanyRepository companyRepository;

    public CompanyService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    public Company registerCompany(CompanyRegistrationRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Request body is required");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Company name is required");
        }
        if (request.taxCode() == null || request.taxCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Tax code is required");
        }
        if (request.registrationCertificateUrl() == null || request.registrationCertificateUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Registration certificate URL is required");
        }

        String taxCode = request.taxCode().trim();
        if (companyRepository.findByTaxCode(taxCode).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "40901 DUPLICATE_TAX_CODE: Tax code already registered: " + taxCode);
        }

        Company company = new Company();
        company.setName(request.name().trim());
        company.setTaxCode(taxCode);
        company.setRegistrationCertificateUrl(request.registrationCertificateUrl().trim());
        company.setVerificationStatus(VerificationStatus.PENDING);
        company.setVersion(0L);

        return companyRepository.save(company);
    }

    @Transactional
    public Company resubmitCompany(Long companyId, CompanyResubmissionRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Request body is required");
        }
        if (request.expectedVersion() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: expectedVersion is required");
        }

        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "40400 COMPANY_NOT_FOUND: Company not found"));

        if (company.getVerificationStatus() != VerificationStatus.REJECTED) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "40012 COMPANY_NOT_REJECTED: Only REJECTED companies can be resubmitted. Current status: " + company.getVerificationStatus()
            );
        }

        if (!company.getVersion().equals(request.expectedVersion())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "40022 CONCURRENT_MODIFICATION: Version mismatch. Expected: " + request.expectedVersion() + ", current: " + company.getVersion()
            );
        }

        if (request.name() != null && !request.name().isBlank()) {
            company.setName(request.name().trim());
        }
        if (request.registrationCertificateUrl() != null && !request.registrationCertificateUrl().isBlank()) {
            company.setRegistrationCertificateUrl(request.registrationCertificateUrl().trim());
        }

        company.setVerificationStatus(VerificationStatus.PENDING);
        company.setRejectionReason(null);
        company.setVersion(company.getVersion() + 1);

        return companyRepository.save(company);
    }

    public Company getCompany(Long id) {
        return companyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "40400 COMPANY_NOT_FOUND: Company not found"));
    }
}
