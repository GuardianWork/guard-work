package com.guardwork.backend.company.service;

import java.util.Optional;
import java.util.regex.Pattern;

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

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

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
        if (request.email() == null || request.email().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Company email is required");
        }
        if (!EMAIL_PATTERN.matcher(request.email().trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Invalid company email format");
        }
        if (request.registrationCertificateUrl() == null || request.registrationCertificateUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Registration certificate URL is required");
        }

        String taxCode = request.taxCode().trim();
        String email = request.email().trim().toLowerCase();

        Optional<Company> existingByTaxCode = companyRepository.findByTaxCode(taxCode);
        Optional<Company> existingByEmail = companyRepository.findByEmail(email);

        Optional<Company> existingOpt = existingByTaxCode.isPresent() ? existingByTaxCode : existingByEmail;
        if (existingOpt.isPresent()) {
            Company existing = existingOpt.get();
            if (existing.getVerificationStatus() == VerificationStatus.REJECTED) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "40902 RESUBMISSION_REQUIRED: Company with tax code '" + existing.getTaxCode() +
                        "' was previously REJECTED. Please resubmit using your existing company record (PUT /api/companies/" +
                        existing.getId() + "/resubmit) with expectedVersion " + existing.getVersion() +
                        " to update your submission and receive newest verification status."
                );
            }
            if (existing.getVerificationStatus() == VerificationStatus.PENDING) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "40903 COMPANY_ALREADY_PENDING: A verification request for tax code '" + existing.getTaxCode() +
                        "' is already pending review (Company ID: " + existing.getId() + ")."
                );
            }
            if (existing.getVerificationStatus() == VerificationStatus.VERIFIED) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "40904 COMPANY_ALREADY_VERIFIED: Company with tax code '" + existing.getTaxCode() +
                        "' is already verified."
                );
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "40901 DUPLICATE_TAX_CODE: Tax code already registered: " + taxCode);
        }

        Company company = new Company();
        company.setName(request.name().trim());
        company.setTaxCode(taxCode);
        company.setEmail(email);
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
        if (request.email() != null && !request.email().isBlank()) {
            String trimmedEmail = request.email().trim();
            if (!EMAIL_PATTERN.matcher(trimmedEmail).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Invalid company email format");
            }
            company.setEmail(trimmedEmail.toLowerCase());
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

    public Company getCompanyByTaxCode(String taxCode) {
        if (taxCode == null || taxCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Tax code is required");
        }
        return companyRepository.findByTaxCode(taxCode.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "40400 COMPANY_NOT_FOUND: Company not found with tax code: " + taxCode.trim()));
    }
}
