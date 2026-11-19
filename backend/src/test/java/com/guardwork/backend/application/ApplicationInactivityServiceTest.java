package com.guardwork.backend.application;

import com.guardwork.backend.application.model.ApplicationSnapshot;
import com.guardwork.backend.application.model.ApplicationStatusHistory;
import com.guardwork.backend.application.model.JobApplication;
import com.guardwork.backend.application.repository.ApplicationSnapshotRepository;
import com.guardwork.backend.application.repository.ApplicationStatusHistoryRepository;
import com.guardwork.backend.application.repository.JobApplicationRepository;
import com.guardwork.backend.application.service.ApplicationInactivityService;
import com.guardwork.backend.candidate.cvs.CandidateCvAccessGrant;
import com.guardwork.backend.candidate.cvs.CandidateCvAccessGrantRepository;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.job.model.JobAssignment;
import com.guardwork.backend.job.model.JobPosting;
import com.guardwork.backend.job.repository.JobAssignmentRepository;
import com.guardwork.backend.job.repository.JobPostingRepository;
import com.guardwork.backend.membership.model.CompanyMembership;
import com.guardwork.backend.membership.model.CompanyRole;
import com.guardwork.backend.membership.model.MembershipStatus;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.notification.model.NotificationAudience;
import com.guardwork.backend.notification.service.NotificationDispatcher;
import com.guardwork.backend.notification.service.NotificationIntentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApplicationInactivityServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(30));
    private static final UUID APPLICATION_ID = UUID.randomUUID();
    private static final long COMPANY_ID = 7L;
    private static final long JOB_ID = 42L;
    private static final long OWNER_ID = 99L;
    private static final UUID CANDIDATE_ID = UUID.randomUUID();

    @Mock JobApplicationRepository applications;
    @Mock ApplicationSnapshotRepository snapshots;
    @Mock ApplicationStatusHistoryRepository history;
    @Mock CandidateCvAccessGrantRepository cvGrants;
    @Mock CompanyRepository companies;
    @Mock JobPostingRepository jobs;
    @Mock JobAssignmentRepository assignments;
    @Mock CompanyMembershipRepository memberships;
    @Mock NotificationIntentService notifications;
    @Mock NotificationDispatcher dispatcher;
    @Mock PlatformTransactionManager manager;
    @Mock TransactionStatus transactionStatus;

    ApplicationInactivityService service;
    JobApplication application;
    Company company;
    ApplicationSnapshot snapshot;

    @BeforeEach
    void setUp() {
        application = new JobApplication();
        application.setId(APPLICATION_ID);
        application.setCandidateId(CANDIDATE_ID);
        application.setCompanyId(COMPANY_ID);
        application.setJobId(JOB_ID);
        application.setStatus("SUBMITTED");
        application.setStatusChangedAt(NOW.minus(Duration.ofDays(31)));

        company = new Company();
        company.setId(COMPANY_ID);
        company.setOwnerUserId(OWNER_ID);

        snapshot = new ApplicationSnapshot();
        snapshot.setJobTitle("Backend Engineer");

        lenient().when(manager.getTransaction(any())).thenReturn(transactionStatus);
        lenient().when(applications.findById(APPLICATION_ID)).thenReturn(Optional.of(application));
        lenient().when(companies.findByIdForUpdate(COMPANY_ID)).thenReturn(Optional.of(company));
        lenient().when(jobs.lock(JOB_ID, COMPANY_ID)).thenReturn(Optional.of(new JobPosting()));
        lenient().when(notifications.recordApplication(
                        any(), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());
        service = new ApplicationInactivityService(applications, snapshots, history, cvGrants,
                companies, jobs, assignments, memberships, notifications, dispatcher,
                Clock.fixed(NOW, ZoneOffset.UTC), manager, Duration.ofDays(30), 100);
    }

    @Test
    void marksStaleApplicationInactiveAndRevokesTheCurrentGrant() {
        when(applications.findInactiveApplicationIds(CUTOFF, 100)).thenReturn(List.of(APPLICATION_ID));
        when(applications.findByIdForUpdate(APPLICATION_ID)).thenReturn(Optional.of(application));
        when(companies.findByIdForUpdate(COMPANY_ID)).thenReturn(Optional.of(company));
        when(assignments.findByJobIdAndEndedAtIsNullOrderById(JOB_ID)).thenReturn(List.of());
        when(snapshots.findById(APPLICATION_ID)).thenReturn(Optional.of(snapshot));

        assertEquals(1, service.expireDue());

        assertEquals("INACTIVE", application.getStatus());
        assertEquals(NOW, application.getStatusChangedAt());
        verify(history).saveAndFlush(argThat(event ->
                "INACTIVE".equals(event.getToStatus())
                        && "SUBMITTED".equals(event.getFromStatus())
                        && event.getActorUserId() == null));
        verify(cvGrants).revoke(APPLICATION_ID, NOW,
                CandidateCvAccessGrant.REASON_APPLICATION_INACTIVE);
        verify(notifications).recordApplication(eq(OWNER_ID), eq("APPLICATION_INACTIVE"),
                eq(NotificationAudience.RECRUITER), eq(company), eq(JOB_ID),
                eq("Backend Engineer"), eq(APPLICATION_ID), isNull(), eq(NOW));
        verify(dispatcher).dispatch(any());
    }

    @Test
    void leavesAnApplicationUpdatedAfterTheCutoffUntouched() {
        application.setStatusChangedAt(NOW.minus(Duration.ofDays(29)));
        when(applications.findInactiveApplicationIds(CUTOFF, 100)).thenReturn(List.of(APPLICATION_ID));
        when(applications.findByIdForUpdate(APPLICATION_ID)).thenReturn(Optional.of(application));

        assertEquals(0, service.expireDue());

        assertEquals("SUBMITTED", application.getStatus());
        verifyNoInteractions(history, cvGrants, notifications, dispatcher);
    }

    @Test
    void leavesAnApplicationThatIsAlreadyInactiveUntouched() {
        application.setStatus("INACTIVE");
        when(applications.findInactiveApplicationIds(CUTOFF, 100)).thenReturn(List.of(APPLICATION_ID));
        when(applications.findByIdForUpdate(APPLICATION_ID)).thenReturn(Optional.of(application));

        assertEquals(0, service.expireDue());

        verifyNoInteractions(history, cvGrants, notifications, dispatcher);
    }

    @Test
    void notifiesTheOwnerAndEveryCurrentlyAssignedRecruiter() {
        Instant joinedAt = NOW.minus(Duration.ofDays(90));
        JobAssignment assignment = new JobAssignment();
        assignment.setJobId(JOB_ID);
        assignment.setCompanyId(COMPANY_ID);
        assignment.setMembershipId(5L);
        assignment.setMembershipJoinedAt(joinedAt);

        CompanyMembership recruiter = new CompanyMembership();
        recruiter.setId(5L);
        recruiter.setCompanyId(COMPANY_ID);
        recruiter.setUserId(77L);
        recruiter.setRole(CompanyRole.RECRUITER);
        recruiter.setStatus(MembershipStatus.ACTIVE);
        recruiter.setJoinedAt(joinedAt);

        when(applications.findInactiveApplicationIds(CUTOFF, 100)).thenReturn(List.of(APPLICATION_ID));
        when(applications.findByIdForUpdate(APPLICATION_ID)).thenReturn(Optional.of(application));
        when(companies.findByIdForUpdate(COMPANY_ID)).thenReturn(Optional.of(company));
        when(assignments.findByJobIdAndEndedAtIsNullOrderById(JOB_ID)).thenReturn(List.of(assignment));
        when(memberships.findByIdAndCompanyForUpdate(5L, COMPANY_ID)).thenReturn(Optional.of(recruiter));
        when(snapshots.findById(APPLICATION_ID)).thenReturn(Optional.of(snapshot));

        assertEquals(1, service.expireDue());

        verify(notifications, times(2)).recordApplication(any(), eq("APPLICATION_INACTIVE"),
                eq(NotificationAudience.RECRUITER), eq(company), eq(JOB_ID),
                eq("Backend Engineer"), eq(APPLICATION_ID), isNull(), eq(NOW));
        verify(dispatcher, times(2)).dispatch(any());
    }
}
