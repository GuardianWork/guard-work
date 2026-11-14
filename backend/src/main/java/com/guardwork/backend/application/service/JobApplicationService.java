package com.guardwork.backend.application.service;

import com.guardwork.backend.application.dto.ApplicationCvDownloadResponse;
import com.guardwork.backend.application.dto.ApplicationCvOption;
import com.guardwork.backend.application.dto.ApplicationDetailResponse;
import com.guardwork.backend.application.dto.ApplicationStatusHistoryResponse;
import com.guardwork.backend.audit.service.AuditService;
import com.guardwork.backend.application.dto.JobApplicationResponse;
import com.guardwork.backend.application.dto.JobApplicationSummaryResponse;
import com.guardwork.backend.application.dto.RecruiterApplicationSummaryResponse;
import com.guardwork.backend.application.dto.CompanyApplicationSummaryResponse;
import com.guardwork.backend.application.dto.SubmitApplicationRequest;
import com.guardwork.backend.application.model.ApplicationSnapshot;
import com.guardwork.backend.application.model.ApplicationStatusHistory;
import com.guardwork.backend.application.model.JobApplication;
import com.guardwork.backend.application.repository.ApplicationSnapshotRepository;
import com.guardwork.backend.application.repository.ApplicationStatusHistoryRepository;
import com.guardwork.backend.application.repository.JobApplicationRepository;
import com.guardwork.backend.candidate.CandidateProfile;
import com.guardwork.backend.candidate.cvs.CandidateCvStorage;
import com.guardwork.backend.common.PageResponse;
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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class JobApplicationService {

    private static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final String STORAGE_PROVIDER = "CLOUDINARY";
    private static final String DUPLICATE_CONSTRAINT = "uk_applications_candidate_job";
    private static final java.time.Duration DOWNLOAD_URL_TTL = java.time.Duration.ofMinutes(15);

    private static final java.util.Map<String, String> CONTENT_TYPE_TO_FORMAT = java.util.Map.of(
            "application/pdf", "pdf",
            "application/msword", "doc",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
    private static final java.util.Map<String, String> FORMAT_TO_RESOURCE_TYPE = java.util.Map.of(
            "pdf", "image",
            "doc", "raw",
            "docx", "raw");

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
    ApplicationStatusHistoryRepository statusHistory;
    CandidateCvStorage cvStorage;
    AuditService audit;
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
        snapshot.setJobLocation(job.location());
        snapshot.setWorkMode(job.workMode());
        snapshot.setJobDeadline(job.deadline());
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
            ApplicationStatusHistory submitted = new ApplicationStatusHistory();
            submitted.setApplicationId(applicationId);
            submitted.setToStatus(STATUS_SUBMITTED);
            submitted.setActorUserId(userId);
            submitted.setOccurredAt(appliedAt);
            statusHistory.saveAndFlush(submitted);
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
                job.location(),
                job.workMode(),
                job.deadline(),
                STATUS_SUBMITTED,
                appliedAt,
                cvSnapshot.fileName()
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<JobApplicationSummaryResponse> list(Long userId, String search, String status, int page, int size) {
        User user = requireActiveUser(userId, false);
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new AppException(ErrorCode.ACCOUNT_SUSPENDED);
        }
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw invalid("pagination");
        }
        if (status != null && !java.util.List.of("SUBMITTED", "UNDER_REVIEW", "SHORTLISTED", "INTERVIEWING",
                "HIRED", "REJECTED", "WITHDRAWN").contains(status)) {
            throw invalid("status");
        }
        String normalizedSearch = normalizeCandidateSearch(search);

        CandidateProfile profile = profiles.getOrCreateProfile(userId);
        UUID candidateId = profile.getId();
        var applicationsPage = applications.findCandidateList(candidateId, normalizedSearch, status,
                PageRequest.of(page, size, Sort.unsorted()));
        var applicationIds = applicationsPage.getContent().stream()
                .map(application -> application.getId()).toList();
        var statusHistoryByApplication = applicationIds.isEmpty()
                ? java.util.Map.<UUID, List<ApplicationStatusHistory>>of()
                : statusHistory.findByApplicationIdInOrderByApplicationIdAscIdAsc(applicationIds).stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                ApplicationStatusHistory::getApplicationId));
        var content = applicationsPage.getContent().stream()
                .map(application -> new JobApplicationSummaryResponse(
                        application.getId(),
                        application.getJobId(),
                        application.getJobTitle(),
                        application.getCompanyName(),
                        application.getJobLocation(),
                        application.getWorkMode(),
                        application.getJobDeadline(),
                        application.getStatus(),
                        application.getAppliedAt(),
                        application.getCvFileName(),
                        application.getVersion() == null ? 0L : application.getVersion(),
                        statusHistoryByApplication.getOrDefault(application.getId(), java.util.List.of())
                                .stream()
                                .map(ApplicationStatusHistory::getCandidateFeedback)
                                .filter(java.util.Objects::nonNull)
                                .reduce((first, second) -> second).orElse(null)))
                .toList();
        return PageResponse.of(applicationsPage, content);
    }

    @Transactional(readOnly = true)
    public ApplicationCvDownloadResponse downloadSubmittedCv(Long userId, UUID applicationId) {
        requireActiveUser(userId, false);
        CandidateProfile profile = profiles.getOrCreateProfile(userId);
        UUID candidateId = profile.getId();
        JobApplication application = applications.findByIdAndCandidateId(applicationId, candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.JOB_NOT_FOUND));
        ApplicationSnapshot snapshot = snapshots.findById(application.getId()).orElseThrow(
                () -> new IllegalStateException("Missing application snapshot"));

        String format = CONTENT_TYPE_TO_FORMAT.get(snapshot.getCvContentType());
        if (format == null) {
            throw new AppException(ErrorCode.INVALID_CV_FILE_FORMAT);
        }
        String resourceType = FORMAT_TO_RESOURCE_TYPE.get(format);
        if (resourceType == null) {
            throw new AppException(ErrorCode.INVALID_CV_FILE_FORMAT);
        }
        var expiresAt = clock.instant().plus(DOWNLOAD_URL_TTL);
        String url = cvStorage.createDownloadUrl(snapshot.getCvStorageKey(), resourceType, format, DOWNLOAD_URL_TTL);
        return new ApplicationCvDownloadResponse(url, expiresAt, snapshot.getCvFileName());
    }

    @Transactional
    public com.guardwork.backend.common.PageResponse<RecruiterApplicationSummaryResponse> listForCompany(
            Long userId, Long jobId, String search, String status, int page, int size) {
        if (jobId == null || jobId <= 0) {
            throw invalidRequest("jobId");
        }

        var access = requireApplicationListingAccess(userId);

        requireListingJobAccess(access, jobId);

        validateListingPaginationAndStatus(page, size, status);
        String normalizedSearch = normalizeSearch(search);

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

    @Transactional
    public com.guardwork.backend.common.PageResponse<CompanyApplicationSummaryResponse>
            listCompanyApplications(Long userId, Long jobId, String search, String status, int page, int size) {
        if (jobId != null && jobId <= 0) throw invalidRequest("jobId");
        var access = requireApplicationListingAccess(userId);
        if (jobId != null) {
            requireListingJobAccess(access, jobId);
        }
        validateListingPaginationAndStatus(page, size, status);
        var result = applications.findCompanyList(access.company().getId(),
                access.membership().getRole() == CompanyRole.OWNER, access.membership().getId(),
                access.membership().getJoinedAt(), jobId, normalizeSearch(search), status,
                PageRequest.of(page, size, Sort.unsorted()));
        return com.guardwork.backend.common.PageResponse.of(result, result.getContent().stream()
                .map(item -> new CompanyApplicationSummaryResponse(
                        item.getId(), item.getCandidateName(), item.getStatus(), item.getAppliedAt(),
                        item.getJobId(), item.getJobTitle()))
                .toList());
    }

    private void requireListingJobAccess(CompanyAccessService.Access access, Long jobId) {
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
    }

    private static void validateListingPaginationAndStatus(int page, int size, String status) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw invalidRequest("pagination");
        }
        if (status != null && !List.of("SUBMITTED", "UNDER_REVIEW", "SHORTLISTED", "INTERVIEWING", "HIRED", "REJECTED", "WITHDRAWN")
                .contains(status)) {
            throw invalidRequest("status");
        }
    }

    private CompanyAccessService.Access requireApplicationListingAccess(Long userId) {
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

        return access;
    }

    @Transactional
    public ApplicationDetailResponse companyDetail(Long userId, Long jobId, UUID applicationId) {
        var access = requireCompanyApplicationAccess(userId, jobId, applicationId);
        ApplicationSnapshot snapshot = snapshots.findById(applicationId).orElseThrow(
                JobApplicationService::listingNotFound);
        JobApplication application = access.application();
        boolean terminal = "REJECTED".equals(application.getStatus())
                || "WITHDRAWN".equals(application.getStatus());
        var history = statusHistory.findByApplicationIdOrderById(applicationId);
        // Resolve attribution only after Company authorization, in one batch.
        Map<Long, String> actorNames = new HashMap<>();
        var actorIds = history.stream().map(ApplicationStatusHistory::getActorUserId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (!actorIds.isEmpty()) {
            users.findAllById(actorIds).forEach(actor -> actorNames.put(actor.getId(),
                    actor.fullName().isBlank() ? actor.getUsername() : actor.fullName()));
        }

        return new ApplicationDetailResponse(
                application.getId(), application.getJobId(),
                terminal ? snapshot.getFullName() : snapshot.getFullName(),
                terminal ? null : snapshot.getEmail(),
                terminal ? null : snapshot.getPhoneNumber(),
                terminal ? null : snapshot.getCoverLetter(),
                snapshot.getJobTitle(), snapshot.getCompanyName(), application.getStatus(),
                application.getVersion(), application.getAppliedAt(),
                terminal ? null : snapshot.getCvFileName(),
                terminal ? null : snapshot.getCvContentType(),
                terminal ? 0 : snapshot.getCvFileSize(),
                history.stream()
                        .map(item -> new ApplicationStatusHistoryResponse(item.getFromStatus(), item.getToStatus(),
                                item.getActorUserId(), actorNames.get(item.getActorUserId()),
                                item.getOccurredAt(), item.getRejectionReason(),
                                item.getInternalExplanation(), item.getCandidateFeedback()))
                        .toList(),
                history.stream()
                        .map(com.guardwork.backend.application.model.ApplicationStatusHistory::getCandidateFeedback)
                        .filter(java.util.Objects::nonNull).reduce((first, second) -> second).orElse(null));
    }

    @Transactional
    public ApplicationDetailResponse candidateDetail(Long userId, UUID applicationId) {
        User user = requireActiveUser(userId, false);
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new AppException(ErrorCode.ACCOUNT_SUSPENDED);
        }
        UUID candidateId = profiles.getOrCreateProfile(userId).getId();
        JobApplication application = applications.findById(applicationId)
                .filter(value -> value.getCandidateId().equals(candidateId))
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        // AD-003: a suspended Job Posting conceals its Applications, mirroring the Candidate list.
        jobPostings.lock(application.getJobId(), application.getCompanyId())
                .filter(job -> !job.isStaffSuspended() && !"SUSPENDED".equals(job.getStatus()))
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        ApplicationSnapshot snapshot = snapshots.findById(applicationId).orElseThrow(
                () -> new IllegalStateException("Missing application snapshot"));
        var history = statusHistory.findByApplicationIdOrderById(applicationId);
        return new ApplicationDetailResponse(
                application.getId(), application.getJobId(), snapshot.getFullName(), snapshot.getEmail(),
                snapshot.getPhoneNumber(), snapshot.getCoverLetter(), snapshot.getJobTitle(),
                snapshot.getCompanyName(), application.getStatus(), application.getVersion(),
                application.getAppliedAt(), snapshot.getCvFileName(), snapshot.getCvContentType(),
                snapshot.getCvFileSize(), history.stream().map(item -> new ApplicationStatusHistoryResponse(
                        item.getFromStatus(), item.getToStatus(), null, null, item.getOccurredAt(), null, null,
                        item.getCandidateFeedback())).toList(),
                history.stream().map(com.guardwork.backend.application.model.ApplicationStatusHistory::getCandidateFeedback)
                        .filter(java.util.Objects::nonNull).reduce((first, second) -> second).orElse(null));
    }

    @Transactional
    public ApplicationCvDownload companyCv(Long userId, Long jobId, UUID applicationId) {
        CompanyApplicationAccess access = requireCompanyApplicationAccess(userId, jobId, applicationId);
        if ("REJECTED".equals(access.application().getStatus())
                || "WITHDRAWN".equals(access.application().getStatus())) {
            throw listingNotFound();
        }
        ApplicationSnapshot snapshot = snapshots.findById(applicationId).orElseThrow(
                JobApplicationService::listingNotFound);
        if (!STORAGE_PROVIDER.equals(snapshot.getCvStorageProvider())) {
            throw new AppException(ErrorCode.CV_STORAGE_UNAVAILABLE);
        }
        MediaType contentType = MediaType.parseMediaType(snapshot.getCvContentType());
        if (!MediaType.APPLICATION_PDF.equals(contentType)) {
            throw new AppException(ErrorCode.CV_PREVIEW_UNSUPPORTED);
        }
        byte[] content = cvStorage.read(snapshot.getCvStorageKey(), "image", "pdf");
        audit.recordLog(userId, "APPLICATION_CV_VIEWED", "APPLICATION", applicationId.toString(),
                null, null, null, null, null);
        return new ApplicationCvDownload(content, contentType, snapshot.getCvFileName());
    }

    private CompanyApplicationAccess requireCompanyApplicationAccess(Long userId, Long jobId, UUID applicationId) {
        var access = companyAccess.findCurrentCompanyAccess(userId);
        if (access == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "40302 NOT_COMPANY_MEMBER: Active Company membership required");
        }
        if (access.membership().getRole() == CompanyRole.ADMIN
                || (access.membership().getRole() == CompanyRole.OWNER
                    && !userId.equals(access.company().getOwnerUserId()))
                || (access.membership().getRole() != CompanyRole.OWNER
                    && access.membership().getRole() != CompanyRole.RECRUITER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "40303 INSUFFICIENT_COMPANY_ROLE: Owner or Recruiter required");
        }
        if (access.company().getVerificationStatus() != VerificationStatus.VERIFIED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "40305 COMPANY_NOT_VERIFIED: Company must be verified");
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
        JobApplication application = applications.findById(applicationId)
                .filter(value -> value.getJobId().equals(jobId)
                        && value.getCompanyId().equals(access.company().getId()))
                .orElseThrow(JobApplicationService::listingNotFound);
        return new CompanyApplicationAccess(application);
    }

    private record CompanyApplicationAccess(JobApplication application) {}

    public record ApplicationCvDownload(byte[] content, MediaType contentType, String fileName) {}

    private static ResponseStatusException invalidRequest(String field) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "40001 INVALID_INPUT: Invalid " + field);
    }

    private static String normalizeCandidateSearch(String search) {
        if (search == null) return null;
        String normalized = search.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.codePointCount(0, normalized.length()) > 150) {
            throw invalid("search");
        }
        return normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
