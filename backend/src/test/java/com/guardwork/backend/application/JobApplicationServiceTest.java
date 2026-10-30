package com.guardwork.backend.application;

import com.guardwork.backend.application.dto.ApplicationFormResponse;
import com.guardwork.backend.application.dto.JobApplicationResponse;
import com.guardwork.backend.application.dto.SubmitApplicationRequest;
import com.guardwork.backend.application.model.ApplicationSnapshot;
import com.guardwork.backend.application.model.JobApplication;
import com.guardwork.backend.application.repository.ApplicationSnapshotRepository;
import com.guardwork.backend.application.repository.JobApplicationRepository;
import com.guardwork.backend.application.service.JobApplicationService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobApplicationServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long JOB_ID = 10L;
    private static final Long COMPANY_ID = 20L;
    private static final Long REVISION_ID = 30L;
    private static final Instant APPLIED_AT = Instant.parse("2026-10-30T09:00:00Z");
    private static final String PDF = "application/pdf";

    @Mock UserRepository users;
    @Mock CandidateProfileService profiles;
    @Mock CandidateCvRepository cvs;
    @Mock CandidateCvService cvService;
    @Mock JobPublicationService jobs;
    @Mock JobApplicationRepository applications;
    @Mock ApplicationSnapshotRepository snapshots;
    @Mock Clock clock;

    @InjectMocks JobApplicationService service;

    private UUID candidateId;
    private UUID cvId;
    private CandidateProfile profile;

    @BeforeEach
    void setUp() {
        candidateId = UUID.randomUUID();
        cvId = UUID.randomUUID();
        profile = new CandidateProfile();
        profile.setId(candidateId);
        profile.setUserId(USER_ID);
        profile.setFullName("Nguyễn Văn An");
        profile.setEmail("an@example.com");
        profile.setPhoneNumber("0901234567");
    }

    private User user(String status) {
        User user = new User();
        user.setId(USER_ID);
        user.setStatus(status);
        return user;
    }

    private JobPublicationService.ApplicationContext availableJob() {
        return new JobPublicationService.ApplicationContext(
                JOB_ID, COMPANY_ID, "Acme", REVISION_ID, "Backend Engineer",
                Instant.parse("2026-11-30T17:00:00Z"), true, null);
    }

    private CandidateCv cv(boolean isDefault) {
        CandidateCv cv = new CandidateCv();
        cv.setId(cvId);
        cv.setCandidateId(candidateId);
        cv.setFileName("CV.pdf");
        cv.setFileSize(2048L);
        cv.setContentType(PDF);
        cv.setDefault(isDefault);
        return cv;
    }

    private SubmitApplicationRequest request(String fullName, String coverLetter) {
        return new SubmitApplicationRequest(
                JOB_ID, fullName, " an@example.com ", " 0901234567 ", coverLetter, cvId);
    }

    private CandidateCvService.CandidateCvSnapshot snapshot() {
        return new CandidateCvService.CandidateCvSnapshot(
                cvId, "applications/abc/cv/snapshot.pdf", "CLOUDINARY", "CV.pdf", PDF, 2048L, "image");
    }

    @Nested
    @DisplayName("form")
    class Form {

        @Test
        void returnsProfileDefaultCvAndAvailability() {
            when(users.findById(USER_ID)).thenReturn(Optional.of(user("ACTIVE")));
            when(profiles.getOrCreateProfile(USER_ID)).thenReturn(profile);
            when(jobs.applicationContext(JOB_ID)).thenReturn(availableJob());
            when(cvs.findByCandidateIdOrderByCreatedAtDesc(candidateId))
                    .thenReturn(List.of(cv(false), cv(true)));
            when(applications.existsByCandidateIdAndJobId(candidateId, JOB_ID)).thenReturn(false);

            ApplicationFormResponse response = service.form(USER_ID, JOB_ID);

            assertEquals(JOB_ID, response.job().id());
            assertEquals("Backend Engineer", response.job().title());
            assertEquals("Acme", response.job().companyName());
            assertEquals("Nguyễn Văn An", response.profile().fullName());
            assertEquals(cvId, response.defaultCvId());
            assertEquals(2, response.cvs().size());
            assertTrue(response.missingProfileFields().isEmpty());
            assertEquals(false, response.alreadyApplied());
        }

        @Test
        void reportsMissingProfileFields() {
            profile.setFullName(" ");
            profile.setEmail(null);
            profile.setPhoneNumber(null);
            when(users.findById(USER_ID)).thenReturn(Optional.of(user("ACTIVE")));
            when(profiles.getOrCreateProfile(USER_ID)).thenReturn(profile);
            when(jobs.applicationContext(JOB_ID)).thenReturn(availableJob());
            when(cvs.findByCandidateIdOrderByCreatedAtDesc(candidateId)).thenReturn(List.of());
            when(applications.existsByCandidateIdAndJobId(candidateId, JOB_ID)).thenReturn(true);

            ApplicationFormResponse response = service.form(USER_ID, JOB_ID);

            assertEquals(List.of("fullName", "email", "phoneNumber"), response.missingProfileFields());
            assertNull(response.defaultCvId());
            assertTrue(response.alreadyApplied());
        }

        @Test
        void rejectsUnavailableJob() {
            when(users.findById(USER_ID)).thenReturn(Optional.of(user("ACTIVE")));
            when(profiles.getOrCreateProfile(USER_ID)).thenReturn(profile);
            when(jobs.applicationContext(JOB_ID)).thenReturn(new JobPublicationService.ApplicationContext(
                    JOB_ID, COMPANY_ID, "Acme", REVISION_ID, "Backend Engineer",
                    Instant.parse("2026-11-30T17:00:00Z"), false, "EXPIRED"));

            AppException failure = assertThrows(AppException.class, () -> service.form(USER_ID, JOB_ID));

            assertEquals(ErrorCode.JOB_NOT_ACCEPTING_APPLICATIONS, failure.getErrorCode());
        }
    }

    @Nested
    @DisplayName("submit")
    class Submit {

        @Test
        void createsApplicationAndImmutableSnapshot() {
            when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user("ACTIVE")));
            when(profiles.getOrCreateProfile(USER_ID)).thenReturn(profile);
            when(jobs.applicationContext(JOB_ID)).thenReturn(availableJob());
            when(applications.existsByCandidateIdAndJobId(candidateId, JOB_ID)).thenReturn(false);
            when(clock.instant()).thenReturn(APPLIED_AT);
            when(cvService.copyForApplication(eq(candidateId), eq(cvId), any(UUID.class)))
                    .thenReturn(snapshot());
            when(applications.saveAndFlush(any(JobApplication.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(snapshots.saveAndFlush(any(ApplicationSnapshot.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            JobApplicationResponse response = service.submit(USER_ID, request("  Nguyễn Văn An  ", "  "));

            assertEquals("SUBMITTED", response.status());
            assertEquals(JOB_ID, response.jobId());
            assertEquals("Backend Engineer", response.jobTitle());
            assertEquals("Acme", response.companyName());
            assertEquals(APPLIED_AT, response.appliedAt());
            assertEquals("CV.pdf", response.cvFileName());

            ArgumentCaptor<JobApplication> application = ArgumentCaptor.forClass(JobApplication.class);
            verify(applications).saveAndFlush(application.capture());
            assertEquals(candidateId, application.getValue().getCandidateId());
            assertEquals(JOB_ID, application.getValue().getJobId());
            assertEquals(COMPANY_ID, application.getValue().getCompanyId());
            assertEquals(REVISION_ID, application.getValue().getSubmittedRevisionId());

            ArgumentCaptor<ApplicationSnapshot> snapshot = ArgumentCaptor.forClass(ApplicationSnapshot.class);
            verify(snapshots).saveAndFlush(snapshot.capture());
            assertEquals("Nguyễn Văn An", snapshot.getValue().getFullName());
            assertEquals("an@example.com", snapshot.getValue().getEmail());
            assertEquals("0901234567", snapshot.getValue().getPhoneNumber());
            assertNull(snapshot.getValue().getCoverLetter());
            assertEquals("Backend Engineer", snapshot.getValue().getJobTitle());
            assertEquals("Acme", snapshot.getValue().getCompanyName());
            assertEquals(cvId, snapshot.getValue().getSourceCvId());
            assertEquals("applications/abc/cv/snapshot.pdf", snapshot.getValue().getCvStorageKey());
        }

        @Test
        void rejectsDuplicateBeforeCopyingCv() {
            when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user("ACTIVE")));
            when(profiles.getOrCreateProfile(USER_ID)).thenReturn(profile);
            when(jobs.applicationContext(JOB_ID)).thenReturn(availableJob());
            when(applications.existsByCandidateIdAndJobId(candidateId, JOB_ID)).thenReturn(true);

            AppException failure = assertThrows(AppException.class,
                    () -> service.submit(USER_ID, request("Nguyễn Văn An", null)));

            assertEquals(ErrorCode.APPLICATION_ALREADY_SUBMITTED, failure.getErrorCode());
            verifyNoInteractions(cvService);
        }

        @Test
        void duplicateRaceDiscardsTheCopiedSnapshot() {
            when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user("ACTIVE")));
            when(profiles.getOrCreateProfile(USER_ID)).thenReturn(profile);
            when(jobs.applicationContext(JOB_ID)).thenReturn(availableJob());
            when(applications.existsByCandidateIdAndJobId(candidateId, JOB_ID)).thenReturn(false);
            when(clock.instant()).thenReturn(APPLIED_AT);
            CandidateCvService.CandidateCvSnapshot snapshot = snapshot();
            when(cvService.copyForApplication(eq(candidateId), eq(cvId), any(UUID.class)))
                    .thenReturn(snapshot);
            when(applications.saveAndFlush(any(JobApplication.class))).thenThrow(
                    new DataIntegrityViolationException("duplicate",
                            new RuntimeException("ERROR: duplicate key value violates unique constraint \"uk_applications_candidate_job\"")));

            AppException failure = assertThrows(AppException.class,
                    () -> service.submit(USER_ID, request("Nguyễn Văn An", null)));

            assertEquals(ErrorCode.APPLICATION_ALREADY_SUBMITTED, failure.getErrorCode());
            verify(cvService).discardSnapshot(snapshot);
            verify(snapshots, never()).saveAndFlush(any());
        }

        @Test
        void copyFailureLeavesNoApplication() {
            when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user("ACTIVE")));
            when(profiles.getOrCreateProfile(USER_ID)).thenReturn(profile);
            when(jobs.applicationContext(JOB_ID)).thenReturn(availableJob());
            when(applications.existsByCandidateIdAndJobId(candidateId, JOB_ID)).thenReturn(false);
            when(cvService.copyForApplication(eq(candidateId), eq(cvId), any(UUID.class)))
                    .thenThrow(new AppException(ErrorCode.APPLICATION_CV_COPY_FAILED));

            AppException failure = assertThrows(AppException.class,
                    () -> service.submit(USER_ID, request("Nguyễn Văn An", null)));

            assertEquals(ErrorCode.APPLICATION_CV_COPY_FAILED, failure.getErrorCode());
            verify(applications, never()).saveAndFlush(any());
        }

        @Test
        void rejectsExpiredJob() {
            when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user("ACTIVE")));
            when(profiles.getOrCreateProfile(USER_ID)).thenReturn(profile);
            when(jobs.applicationContext(JOB_ID)).thenReturn(new JobPublicationService.ApplicationContext(
                    JOB_ID, COMPANY_ID, "Acme", REVISION_ID, "Backend Engineer",
                    Instant.parse("2026-11-30T17:00:00Z"), false, "EXPIRED"));

            AppException failure = assertThrows(AppException.class,
                    () -> service.submit(USER_ID, request("Nguyễn Văn An", null)));

            assertEquals(ErrorCode.JOB_NOT_ACCEPTING_APPLICATIONS, failure.getErrorCode());
            verifyNoInteractions(cvService);
        }

        @Test
        void mapsMissingJobToNotFound() {
            when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user("ACTIVE")));
            when(profiles.getOrCreateProfile(USER_ID)).thenReturn(profile);
            when(jobs.applicationContext(JOB_ID))
                    .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

            AppException failure = assertThrows(AppException.class,
                    () -> service.submit(USER_ID, request("Nguyễn Văn An", null)));

            assertEquals(ErrorCode.JOB_NOT_FOUND, failure.getErrorCode());
            verifyNoInteractions(cvService);
        }

        @Test
        void rejectsSuspendedAccountBeforeJobLookup() {
            when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user("SUSPENDED")));

            AppException failure = assertThrows(AppException.class,
                    () -> service.submit(USER_ID, request("Nguyễn Văn An", null)));

            assertEquals(ErrorCode.ACCOUNT_SUSPENDED, failure.getErrorCode());
            verifyNoInteractions(jobs, cvService);
        }

        @Test
        void rejectsInvalidRequest() {
            when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user("ACTIVE")));

            AppException failure = assertThrows(AppException.class,
                    () -> service.submit(USER_ID, request(" ", null)));

            assertEquals(ErrorCode.INVALID_INPUT, failure.getErrorCode());
            verifyNoInteractions(jobs, cvService);
        }
    }
}
