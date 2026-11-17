package com.guardwork.backend.company.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import jakarta.validation.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.guardwork.backend.company.dto.CompanyRegistrationRequest;
import com.guardwork.backend.company.dto.CompanyResubmissionRequest;
import com.guardwork.backend.company.dto.CurrentCompanyResponse;
import com.guardwork.backend.company.dto.UpdateCompanyProfileRequest;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.CompanyContent;
import com.guardwork.backend.company.model.VerificationStatus;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.membership.model.CompanyMembership;
import com.guardwork.backend.membership.model.CompanyRole;
import com.guardwork.backend.membership.model.MembershipStatus;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.membership.service.CompanyAccessService;
import com.guardwork.backend.membership.service.MembershipAuditService;

@Service
public class CompanyService {

    private static final Logger log = LoggerFactory.getLogger(CompanyService.class);

    private static final java.util.regex.Pattern WORKING_HOURS_REGEX =
            java.util.regex.Pattern.compile(CompanyContent.WORKING_HOURS_PATTERN);

    private final CompanyRepository companyRepository;

    private final CompanyMembershipRepository memberships;
    private final MembershipAuditService audit;
    private final CompanyAccessService access;
    private final Validator validator;

    public CompanyService(CompanyRepository companyRepository,
                          CompanyMembershipRepository memberships, MembershipAuditService audit, CompanyAccessService access, Validator validator) {
        this.companyRepository = companyRepository;
        this.memberships = memberships;
        this.audit = audit;
        this.access = access;
        this.validator = validator;
    }

    @Transactional
    public Company registerCompanyFromCompleteDossier(Long actorId, CompanyRegistrationRequest request) {
        log.debug("event=company_registration outcome=started");
        var actor = access.requireActiveUser(actorId);
        com.guardwork.backend.auth.service.AccountEmailVerificationService.requireRecruitingEmailEligible(actor);
        var activeMembership = memberships.findByUserIdAndStatus(actorId, MembershipStatus.ACTIVE);
        if (activeMembership.isPresent()) {
            if (activeMembership.get().getRole() == CompanyRole.OWNER
                    && access.requireOwner(actorId).company().getVerificationStatus() == VerificationStatus.REJECTED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "40902 RESUBMISSION_REQUIRED: Correct and resubmit your existing rejected Company");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "40910 ACTIVE_COMPANY_MEMBERSHIP_EXISTS: Active Company membership already exists");
        }
        Company company = createCompanyRecord(request);
        company.setOwnerUserId(actorId);
        companyRepository.saveAndFlush(company);
        CompanyMembership membership = new CompanyMembership();
        membership.setCompanyId(company.getId());
        membership.setUserId(actorId);
        membership.setRole(CompanyRole.OWNER);
        membership.setStatus(MembershipStatus.ACTIVE);
        memberships.saveAndFlush(membership);
        audit.record(actorId, actorId, company.getId(), "COMPANY_CREATED", membership.getId(), null, null, null);
        log.debug("event=company_registration outcome=saved_pending_verification");
        return company;
    }

    private Company createCompanyRecord(CompanyRegistrationRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Request body is required");
        }
        if (!validator.validateProperty(request, "name").isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Company name is required");
        }
        if (!validator.validateProperty(request, "taxCode").isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Tax code is required");
        }
        if (request.email() == null || request.email().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Company email is required");
        }
        if (!validator.validateValue(CompanyRegistrationRequest.class, "email", request.email().trim()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Invalid company email format");
        }
        if (!validator.validateProperty(request, "registrationCertificateUrl").isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Registration certificate URL is required");
        }

        validateLength(request.name().trim(), 255, "Company name");
        validateLength(request.taxCode().trim(), 50, "Tax code");
        validateLength(request.email().trim(), 255, "Company email");

        String taxCode = request.taxCode().trim();
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        Optional<Company> existingByTaxCode = companyRepository.findByTaxCode(taxCode);

        if (existingByTaxCode.isPresent()) {
            log.debug("event=company_registration outcome=existing_record");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "40901 DUPLICATE_TAX_CODE: Company registration details already registered");
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
    public Company resubmitCompany(Long actorId, Long companyId, CompanyResubmissionRequest request) {
        return correctRejectedCompany(access.requireOwnerOf(actorId, companyId), request);
    }

    private Company correctRejectedCompany(Company company, CompanyResubmissionRequest request) {
        log.debug("event=company_resubmission outcome=started");
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Request body is required");
        }
        if (!validator.validateProperty(request, "expectedVersion").isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: expectedVersion is required");
        }

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

        if (request.name() != null) {
            String name = requireCorrectionValue(request.name(), "Company name");
            validateLength(name, 255, "Company name");
            company.setName(name);
        }
        if (request.email() != null) {
            String trimmedEmail = requireCorrectionValue(request.email(), "Company email");
            validateLength(trimmedEmail, 255, "Company email");
            if (!validator.validateValue(CompanyResubmissionRequest.class, "email", trimmedEmail).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Invalid company email format");
            }
            String email = trimmedEmail.toLowerCase(Locale.ROOT);
            if (companyRepository.existsByEmailAndIdNot(email, company.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "40901 DUPLICATE_TAX_CODE: Company registration details already registered");
            }
            company.setEmail(email);
        }
        if (request.registrationCertificateUrl() != null) {
            company.setRegistrationCertificateUrl(requireCorrectionValue(request.registrationCertificateUrl(), "Registration certificate URL"));
        }

        company.setVerificationStatus(VerificationStatus.PENDING);
        company.setRejectionReason(null);
        company.setVersion(company.getVersion() + 1);

        Company saved = companyRepository.saveAndFlush(company);
        log.debug("event=company_resubmission outcome=saved_pending_verification");
        return saved;
    }

    /**
     * Replace the company's public profile payload (schemaVersion 1). Only OWNER and ADMIN
     * memberships may write it; RECRUITER is read-only for company-level content. Omitted
     * fields are cleared; an empty request clears the profile. The payload is self-declared
     * by the company and deliberately distinct from GuardianWork-verified facts and from the
     * posting-derived summary. See docs/software-requirements/can/company-review-api-spec.md.
     */
    @Transactional
    public CompanyContent updateProfile(Long actorId, UpdateCompanyProfileRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Request body is required");
        }
        var scope = access.findCurrentCompanyAccess(actorId);
        if (scope == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "40302 NOT_COMPANY_MEMBER: Active Company membership required");
        }
        if (scope.membership().getRole() != CompanyRole.OWNER
                && scope.membership().getRole() != CompanyRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "40303 INSUFFICIENT_COMPANY_ROLE: Owner or Admin required");
        }

        CompanyContent normalized = normalizeProfile(request);
        var violations = validator.validate(normalized);
        if (!violations.isEmpty()) {
            var violation = violations.iterator().next();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "40001 INVALID_INPUT: " + violation.getPropertyPath() + " " + violation.getMessage());
        }

        Company company = scope.company();
        company.setContent(normalized.payloadEmpty() ? CompanyContent.empty() : normalized.stamped());
        companyRepository.saveAndFlush(company);
        audit.record(actorId, actorId, company.getId(), "COMPANY_PROFILE_UPDATED",
                scope.membership().getId(), null, null, null);
        log.debug("event=company_profile_updated outcome=saved");
        return company.getContent();
    }

    private CompanyContent normalizeProfile(UpdateCompanyProfileRequest request) {
        String workingHours = blankToNull(request.workingHours());
        // Day-order is only checked on values that already satisfy the pattern; Bean Validation
        // reports the format violation otherwise, so this stays a plain index-safe check.
        if (workingHours != null && WORKING_HOURS_REGEX.matcher(workingHours).matches()) {
            validateWorkingHoursDayOrder(workingHours);
        }
        return new CompanyContent(
                CompanyContent.SCHEMA_VERSION,
                blankToNull(request.industry()),
                blankToNull(request.introduction()),
                blankToNull(request.workAddress()),
                workingHours,
                request.companyScale(),
                normalizeExpertise(request.expertise()));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Day tokens are Vietnamese weekday labels ("Thứ 2" .. "Thứ 7", "Chủ nhật"); the pattern was
     * already enforced on input, so this only checks that the range starts on or before its end.
     */
    private void validateWorkingHoursDayOrder(String workingHours) {
        String range = workingHours.split(",", 2)[0];
        String[] tokens = range.split(" - ");
        int start = weekdayOrder(tokens[0]);
        int end = weekdayOrder(tokens[1]);
        if (start > end) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "40001 INVALID_INPUT: workingHours start day must not be after end day");
        }
    }

    private static int weekdayOrder(String token) {
        token = token.trim();
        if ("Chủ nhật".equals(token)) {
            return 8;
        }
        return Integer.parseInt(token.substring("Thứ ".length()));
    }

    private static List<String> normalizeExpertise(List<String> expertise) {
        if (expertise == null) {
            return null;
        }
        var seen = new java.util.LinkedHashSet<String>();
        var normalized = new java.util.ArrayList<String>(expertise.size());
        for (String item : expertise) {
            String trimmed = item == null ? "" : item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String key = trimmed.toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                normalized.add(trimmed);
            }
        }
        return normalized.isEmpty() ? null : List.copyOf(normalized);
    }

    @Transactional
    public Company getCompany(Long actorId, Long id) {
        return access.requireOwnerOf(actorId, id);
    }

    @Transactional
    public Company getCompanyByTaxCode(Long actorId, String taxCode) {
        var current = access.findCurrentCompanyAccess(actorId);
        if (current == null || current.membership().getRole() != CompanyRole.OWNER
                || taxCode == null || !current.company().getTaxCode().equals(taxCode.trim())) {
            throw CompanyAccessService.notFound();
        }
        return current.company();
    }

    @Transactional
    public CurrentCompanyResponse getCurrentCompany(Long actorId) {
        var current = access.findCurrentCompanyAccessForStatus(actorId);
        return current == null ? null : new CurrentCompanyResponse(current.company().getId(), current.company().getName(),
                current.membership().getRole().name(), current.company().isBanned());
    }

    @Transactional
    public Company getOwnerVerification(Long actorId) {
        return access.requireOwnerForStatus(actorId).company();
    }

    private void validateLength(String value, int maximum, String field) {
        if (value.codePointCount(0, value.length()) > maximum) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "40000 BAD_REQUEST: " + field + " exceeds " + maximum + " characters");
        }
    }

    private String requireCorrectionValue(String value, String field) {
        if (value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: " + field + " is required");
        }
        return value.trim();
    }
}
