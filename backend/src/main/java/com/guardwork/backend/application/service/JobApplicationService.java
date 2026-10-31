package com.guardwork.backend.application.service;

import com.guardwork.backend.application.dto.ApplicationCvOption;
import com.guardwork.backend.application.dto.ApplicationFormResponse;
import com.guardwork.backend.application.dto.JobApplicationResponse;
import com.guardwork.backend.application.dto.JobSummary;
import com.guardwork.backend.application.dto.ProfilePrefill;
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
    JobApplicationRepository applications;
    ApplicationSnapshotRepository snapshots;
    Clock clock;

    @Transactional
    public ApplicationFormResponse form(Long userId, Long jobId) {
        requireActiveUser(userId, false);
        CandidateProfile profile = profiles.getOrCreateProfile(userId);
        JobPublicationService.ApplicationContext job = requireAvailableJob(jobId);

        List<CandidateCv> candidateCvs = cvs.findByCandidateIdOrderByCreatedAtDesc(profile.getId());
        UUID defaultCvId = candidateCvs.stream()
                .filter(CandidateCv::isDefault)
                .map(CandidateCv::getId)
                .findFirst()
                .orElse(null);

        List<String> missingFields = new ArrayList<>();
        if (profile.getFullName() == null || profile.getFullName().isBlank()) {
            missingFields.add("fullName");
        }
        if (profile.getEmail() == null || profile.getEmail().isBlank()) {
            missingFields.add("email");
        }
        if (profile.getPhoneNumber() == null || profile.getPhoneNumber().isBlank()) {
            missingFields.add("phoneNumber");
        }

        return new ApplicationFormResponse(
                new JobSummary(job.jobId(), job.title(), job.companyId(), job.companyName(), job.deadline()),
                new ProfilePrefill(profile.getFullName(), profile.getEmail(), profile.getPhoneNumber()),
                candidateCvs.stream()
                        .map(cv -> new ApplicationCvOption(
                                cv.getId(), cv.getFileName(), cv.getContentType(), cv.getFileSize(), cv.isDefault()))
                        .toList(),
                defaultCvId,
                List.copyOf(missingFields),
                applications.existsByCandidateIdAndJobId(profile.getId(), jobId)
        );
    }

    @Transactional
    public JobApplicationResponse submit(Long userId, SubmitApplicationRequest request) {
        requireActiveUser(userId, true);
        validate(request);

        CandidateProfile profile = profiles.getOrCreateProfile(userId);
        UUID candidateId = profile.getId();
        JobPublicationService.ApplicationContext job = requireAvailableJob(request.jobId());

        if (applications.existsByCandidateIdAndJobId(candidateId, request.jobId())) {
            throw new AppException(ErrorCode.APPLICATION_ALREADY_SUBMITTED);
        }

        UUID applicationId = UUID.randomUUID();
        CandidateCvService.CandidateCvSnapshot cvSnapshot = cvService.copyForApplication(
                candidateId, request.cvId(), applicationId);

        String fullName = request.fullName().strip();
        String email = request.email().strip();
        String phoneNumber = request.phoneNumber().strip();
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

    private User requireActiveUser(Long userId, boolean lock) {
        User user = (lock ? users.findByIdForUpdate(userId) : users.findById(userId))
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        if (!"ACTIVE".equals(user.getStatus())) {
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
        if (request.phoneNumber() == null
                || !request.phoneNumber().strip().matches("^0[0-9]{9}$")) {
            throw invalid("phoneNumber");
        }
        if (request.coverLetter() != null && request.coverLetter().strip().length() > 1000) {
            throw invalid("coverLetter");
        }
        if (request.cvId() == null) {
            throw invalid("cvId");
        }
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
