package com.guardwork.backend.application.service;

import com.guardwork.backend.application.dto.ApplicationCvOption;
import com.guardwork.backend.application.dto.JobApplicationResponse;
import com.guardwork.backend.application.dto.JobApplicationSummaryResponse;
import com.guardwork.backend.application.dto.RecruiterApplicationSummaryResponse;
import com.guardwork.backend.application.dto.SubmitApplicationRequest;
import com.guardwork.backend.application.model.ApplicationSnapshot;
import com.guardwork.backend.application.model.JobApplication;
import com.guardwork.backend.application.repository.ApplicationSnapshotRepository;
import com.guardwork.backend.application.repository.JobApplicationRepository;
import com.guardwork.backend.candidate.CandidateProfile;
import com.guardwork.backend.candidate.CandidateProfileService;
import com.guardwork.backend.candidate.cvs.CandidateCv;
import com.guardwork.backend.candidate.cvs.CandidateCvRepository;
import com.guardwork.backend.candidate.cvs.CandidateCvService;
import com.guardwork.backend.common.exception.AppException;
import com.guardwork.backend.common.exception.ErrorCode;
import com.guardwork.backend.job.service.JobPublicationService;
import com.guardwork.backend.job.model.JobPosting;
import com.guardwork.backend.job.repository.JobAssignmentRepository;
import com.guardwork.backend.job.repository.JobPostingRepository;
import com.guardwork.backend.membership.model.CompanyRole;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.membership.service.CompanyAccessService;
import com.guardwork.backend.company.model.VerificationStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class JobApplicationService {

    private static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final String STORAGE_PROVIDER = "CLOUDINARY";
    private static final String DUPLICATE_CONSTRAINT = "uk_applications_candidate_job";

    UserRepository users;
    CandidateProfileService profiles;
    CandidateCvRepository cvs;
    CandidateCvService cvService;
    JobPublicationService jobs;
    CompanyMembershipRepository memberships;
    CompanyAccessService companyAccess;
    JobPostingRepository jobPostings;
    JobAssignmentRepository assignments;
    JobApplicationRepository applications;
    ApplicationSnapshotRepository snapshots;
    Clock clock;

    @Transactional
    public JobApplicationResponse submit(Long userId, SubmitApplicationRequest request) {
        requireActiveUser(userId, true);
        validate(request);

        CandidateProfile profile = profiles.getOrCreateProfile(userId);
        UUID candidateId = profile.getId();
        JobPublicationService.ApplicationContext job = requireAvailableJob(request.jobId());

        if (memberships.findActiveCompanyId(userId).filter(job.companyId()::equals).isPresent()) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }

        if (applications.existsByCandidateIdAndJobId(candidateId, request.jobId())) {
            throw new AppException(ErrorCode.APPLICATION_ALREADY_SUBMITTED);
        }

        UUID applicationId = UUID.randomUUID();
        CandidateCvService.CandidateCvSnapshot cvSnapshot = cvService.copyForApplication(
                candidateId, request.cvId(), applicationId);

        String fullName = request.fullName().strip();
        String email = request.email().strip();
        String phoneNumber = normalizeApplicationPhone(request.phoneNumber());
        String coverLetter = normalizeCoverLetter(request.coverLetter());
        var appliedAt = clock.instant();

        JobApplication application = new JobApplication();
        application.setId(applicationId);
        application.setCandidateId(candidateId);
        application.setJobId(job.jobId());
        application.setCompanyId(job.companyId());
        application.setSubmittedRevisionId(job.submittedRevisionId());
        application.setStatus(STATUS_SUBMITTED);
        application.setAppliedAt(appliedAt);
        application.setUpdatedAt(appliedAt);

        ApplicationSnapshot snapshot = new ApplicationSnapshot();
        snapshot.setApplicationId(applicationId);
        snapshot.setFullName(fullName);
        snapshot.setEmail(email);
        snapshot.setPhoneNumber(phoneNumber);
        snapshot.setCoverLetter(coverLetter);
        snapshot.setJobTitle(job.title());
        snapshot.setCompanyName(job.companyName());
        snapshot.setSourceCvId(cvSnapshot.sourceCvId());
        snapshot.setCvStorageKey(cvSnapshot.storageKey());
        snapshot.setCvStorageProvider(STORAGE_PROVIDER);
        snapshot.setCvFileName(cvSnapshot.fileName());
        snapshot.setCvContentType(cvSnapshot.contentType());
        snapshot.setCvFileSize(cvSnapshot.fileSize());
        snapshot.setCapturedAt(appliedAt);

        try {
            applications.saveAndFlush(application);
            snapshots.saveAndFlush(snapshot);
        } catch (DataIntegrityViolationException failure) {
            cvService.discardSnapshot(cvSnapshot);
            if (isDuplicateApplication(failure)) {
                throw new AppException(ErrorCode.APPLICATION_ALREADY_SUBMITTED);
            }
            throw failure;
        } catch (RuntimeException failure) {
            cvService.discardSnapshot(cvSnapshot);
            throw failure;
        }

        log.info("event=application_submitted application_id={} job_id={} company_id={}",
                applicationId, job.jobId(), job.companyId());

        return new JobApplicationResponse(
                applicationId,
                job.jobId(),
                job.title(),
                job.companyId(),
                job.companyName(),
                STATUS_SUBMITTED,
                appliedAt,
                cvSnapshot.fileName()
        );
    }

    @Transactional
    public java.util.List<JobApplicationSummaryResponse> list(Long userId) {
        User user = requireActiveUser(userId, false);
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new AppException(ErrorCode.ACCOUNT_SUSPENDED);
        }
        CandidateProfile profile = profiles.getOrCreateProfile(userId);
        UUID candidateId = profile.getId();
        return applications.findByCandidateIdOrderByAppliedAtDesc(candidateId).stream()
                .map(application -> {
                    ApplicationSnapshot snapshot = snapshots.findById(application.getId()).orElseThrow(
                            () -> new IllegalStateException("Missing application snapshot"));
                    return new JobApplicationSummaryResponse(
                            application.getId(),
                            application.getJobId(),
                            snapshot.getJobTitle(),
                            snapshot.getCompanyName(),
                            application.getStatus(),
                            application.getAppliedAt(),
                            snapshot.getCvFileName());
                })
                .toList();
    }

    @Transactional
    public com.guardwork.backend.common.PageResponse<RecruiterApplicationSummaryResponse> listForCompany(
            Long userId, Long jobId, String search, String status, int page, int size) {
        if (jobId == null || jobId <= 0) {
            throw invalidRequest("jobId");
        }

        var access = companyAccess.findCurrentCompanyAccess(userId);
        if (access == null) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "40302 NOT_COMPANY_MEMBER: Active Company membership required");
        }
        if (access.membership().getRole() == CompanyRole.ADMIN) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "40303 INSUFFICIENT_COMPANY_ROLE: Owner or Recruiter required");
        }
        if (access.membership().getRole() == CompanyRole.OWNER
                && !userId.equals(access.company().getOwnerUserId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "40303 INSUFFICIENT_COMPANY_ROLE: Company Owner required");
        }
        if (access.membership().getRole() != CompanyRole.OWNER
                && access.membership().getRole() != CompanyRole.RECRUITER) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "40303 INSUFFICIENT_COMPANY_ROLE: Owner or Recruiter required");
        }
        if (access.company().getVerificationStatus() != VerificationStatus.VERIFIED) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "40305 COMPANY_NOT_VERIFIED: Company must be verified");
        }

        JobPosting job = jobPostings.lock(jobId, access.company().getId())
                .orElseThrow(JobApplicationService::listingNotFound);
        if (job.isStaffSuspended() || "SUSPENDED".equals(job.getStatus())) {
            throw listingNotFound();
        }
        if (access.membership().getRole() == CompanyRole.RECRUITER
                && assignments.current(jobId).stream().noneMatch(assignment ->
                        assignment.getCompanyId().equals(access.company().getId())
                                && assignment.getMembershipId().equals(access.membership().getId())
                                && assignment.getMembershipJoinedAt().equals(access.membership().getJoinedAt()))) {
            throw listingNotFound();
        }

        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw invalidRequest("pagination");
        }
        String normalizedSearch = normalizeSearch(search);
        if (status != null && !"SUBMITTED".equals(status)) {
            throw invalidRequest("status");
        }

        var applicationsPage = applications.findRecruiterList(
                access.company().getId(), jobId, normalizedSearch, status,
                PageRequest.of(page, size, Sort.unsorted()));
        var content = applicationsPage.getContent().stream()
                .map(application -> new RecruiterApplicationSummaryResponse(
                        application.getId(), application.getCandidateName(),
                        application.getStatus(), application.getAppliedAt()))
                .toList();
        return com.guardwork.backend.common.PageResponse.of(applicationsPage, content);
    }

    private static ResponseStatusException invalidRequest(String field) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "40001 INVALID_INPUT: Invalid " + field);
    }

    private static String normalizeSearch(String search) {
        if (search == null) return null;
        String normalized = search.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.codePointCount(0, normalized.length()) > 150) {
            throw invalidRequest("search");
        }
        return normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static ResponseStatusException listingNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "40478 JOB_NOT_FOUND: Job not found");
    }

    private User requireActiveUser(Long userId, boolean lock) {
        User user = (lock ? users.findByIdForUpdate(userId) : users.findById(userId))
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        if (!user.allowsNormalAccess()) {
            throw new AppException(ErrorCode.ACCOUNT_SUSPENDED);
        }
        return user;
    }

    private JobPublicationService.ApplicationContext requireAvailableJob(Long jobId) {
        JobPublicationService.ApplicationContext job;
        try {
            job = jobs.applicationContext(jobId);
        } catch (ResponseStatusException failure) {
            if (failure.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw new AppException(ErrorCode.JOB_NOT_FOUND);
            }
            throw failure;
        }
        if (!job.available()) {
            throw new AppException(ErrorCode.JOB_NOT_ACCEPTING_APPLICATIONS);
        }
        if (job.title() == null || job.title().isBlank()) {
            throw new AppException(ErrorCode.JOB_NOT_FOUND);
        }
        return job;
    }

    private static void validate(SubmitApplicationRequest request) {
        if (request == null) {
            throw invalid("request");
        }
        if (request.jobId() == null || request.jobId() <= 0) {
            throw invalid("jobId");
        }
        if (request.fullName() == null || request.fullName().isBlank()
                || request.fullName().strip().length() > 150) {
            throw invalid("fullName");
        }
        if (request.email() == null || request.email().isBlank()
                || request.email().strip().length() > 255
                || !request.email().strip().contains("@")) {
            throw invalid("email");
        }
        String phone = request.phoneNumber() == null ? null : request.phoneNumber().strip();
        if (phone == null || !phone.matches("^(0|\\+84)[0-9]{9}$")
                || !normalizeApplicationPhone(phone).matches("^0[0-9]{9}$")) {
            throw invalid("phoneNumber");
        }
        if (request.coverLetter() != null && request.coverLetter().strip().length() > 1000) {
            throw invalid("coverLetter");
        }
        if (request.cvId() == null) {
            throw invalid("cvId");
        }
    }

    private static String normalizeApplicationPhone(String value) {
        String phone = value.strip();
        if (phone.startsWith("+84")) {
            phone = "0" + phone.substring(3);
        }
        return phone;
    }

    private static String normalizeCoverLetter(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isDuplicateApplication(DataIntegrityViolationException failure) {
        Throwable cause = failure.getMostSpecificCause();
        return cause != null && cause.getMessage() != null
                && cause.getMessage().contains(DUPLICATE_CONSTRAINT);
    }

    private static AppException invalid(String field) {
        return new AppException(ErrorCode.INVALID_INPUT, "Invalid " + field);
    }
}
