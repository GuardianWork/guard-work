package com.guardwork.backend.admin.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.admin.dto.CompanyVerificationResponse;
import com.guardwork.backend.admin.dto.VerificationDecisionRequest;
import com.guardwork.backend.audit.service.AuditService;
import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.VerificationStatus;
import com.guardwork.backend.company.repository.CompanyRepository;

@Service
public class AdminCompanyVerificationService {

    private final CompanyRepository companyRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public AdminCompanyVerificationService(CompanyRepository companyRepository,
                                           AuditService auditService,
                                           ObjectMapper objectMapper) {
        this.companyRepository = companyRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public PageResponse<CompanyVerificationResponse> getVerificationQueue(String statusFilter, int page, int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: page must not be negative");
        }
        if (size <= 0 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: size must be between 1 and 100");
        }

        int offset = page * size;
        String filter = (statusFilter == null || statusFilter.isBlank()) ? "PENDING" : statusFilter.trim().toUpperCase();

        List<Company> companies;
        long total;

        switch (filter) {
            case "PENDING" -> {
                companies = companyRepository.findByStatus(VerificationStatus.PENDING, offset, size);
                total = companyRepository.countByStatus(VerificationStatus.PENDING);
            }
            case "VERIFIED" -> {
                companies = companyRepository.findByStatus(VerificationStatus.VERIFIED, offset, size);
                total = companyRepository.countByStatus(VerificationStatus.VERIFIED);
            }
            case "REJECTED" -> {
                companies = companyRepository.findByStatus(VerificationStatus.REJECTED, offset, size);
                total = companyRepository.countByStatus(VerificationStatus.REJECTED);
            }
            case "ALL" -> {
                companies = companyRepository.findAll(offset, size);
                total = companyRepository.countAll();
            }
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "40000 BAD_REQUEST: Invalid status filter: " + statusFilter + ". Allowed: PENDING, VERIFIED, REJECTED, ALL"
            );
        }

        List<CompanyVerificationResponse> content = companies.stream()
                .map(CompanyVerificationResponse::from)
                .toList();

        return PageResponse.of(content, page, size, total);
    }

    @Transactional
    public CompanyVerificationResponse rejectCompany(Long companyId,
                                                     VerificationDecisionRequest request,
                                                     Long adminId,
                                                     String ipAddress,
                                                     String userAgent) {
        VerificationDecisionRequest decisionRequest = new VerificationDecisionRequest(
                VerificationStatus.REJECTED,
                request != null ? request.rejectionReason() : null,
                request != null ? request.expectedVersion() : null
        );
        return verifyCompany(companyId, decisionRequest, adminId, ipAddress, userAgent);
    }

    @Transactional
    public CompanyVerificationResponse verifyCompany(Long companyId,
                                                     VerificationDecisionRequest request,
                                                     Long adminId,
                                                     String ipAddress,
                                                     String userAgent) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Request body is required");
        }
        if (request.status() == null || request.status() == VerificationStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Decision status must be VERIFIED or REJECTED");
        }
        if (request.expectedVersion() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: expectedVersion is required");
        }

        Optional<Company> companyOpt = companyRepository.findById(companyId);
        if (companyOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "40400 COMPANY_NOT_FOUND: Company with ID " + companyId + " not found");
        }

        Company company = companyOpt.get();

        // BR-REC-01: Eligible Verification Status
        if (company.getVerificationStatus() != VerificationStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "40010 COMPANY_NOT_PENDING: Company is not in PENDING status (current status: " + company.getVerificationStatus() + ")"
            );
        }

        // BR-REC-02: Optimistic Concurrency Control
        if (!company.getVersion().equals(request.expectedVersion())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "40022 CONCURRENT_MODIFICATION: Version mismatch. Expected version: " + request.expectedVersion() + ", current version: " + company.getVersion()
            );
        }

        // BR-REC-03: Mandatory Rejection Feedback
        String trimmedReason = null;
        if (request.status() == VerificationStatus.REJECTED) {
            if (request.rejectionReason() == null || request.rejectionReason().trim().length() < 10 || request.rejectionReason().trim().length() > 1000) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "40011 INVALID_REJECTION_REASON: rejectionReason must be between 10 and 1000 characters for REJECTED status"
                );
            }
            trimmedReason = request.rejectionReason().trim();
        }

        // Serialize old state for audit logging
        String oldPayload = serializeToJson(CompanyVerificationResponse.from(company));

        Instant now = Instant.now();
        int rowsUpdated = companyRepository.updateVerification(
                companyId,
                request.status(),
                trimmedReason,
                adminId,
                now,
                request.expectedVersion()
        );

        if (rowsUpdated == 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "40022 CONCURRENT_MODIFICATION: Concurrent modification detected during update"
            );
        }

        Company updatedCompany = companyRepository.findById(companyId).orElseThrow();
        String newPayload = serializeToJson(CompanyVerificationResponse.from(updatedCompany));

        // BR-REC-06: Immutable Audit Trail
        String auditReason = trimmedReason != null ? trimmedReason : "Approved by admin " + adminId;
        auditService.recordLog(
                adminId,
                "COMPANY_VERIFICATION",
                "COMPANY",
                String.valueOf(companyId),
                oldPayload,
                newPayload,
                auditReason,
                ipAddress,
                userAgent
        );

        return CompanyVerificationResponse.from(updatedCompany);
    }

    private String serializeToJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
