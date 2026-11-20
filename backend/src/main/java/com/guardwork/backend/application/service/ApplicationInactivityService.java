package com.guardwork.backend.application.service;

import com.guardwork.backend.application.model.ApplicationSnapshot;
import com.guardwork.backend.application.model.ApplicationStatusHistory;
import com.guardwork.backend.application.model.JobApplication;
import com.guardwork.backend.application.repository.ApplicationSnapshotRepository;
import com.guardwork.backend.application.repository.ApplicationStatusHistoryRepository;
import com.guardwork.backend.application.repository.JobApplicationRepository;
import com.guardwork.backend.candidate.cvs.CandidateCvAccessGrant;
import com.guardwork.backend.candidate.cvs.CandidateCvAccessGrantRepository;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.job.repository.JobAssignmentRepository;
import com.guardwork.backend.job.repository.JobPostingRepository;
import com.guardwork.backend.membership.model.CompanyRole;
import com.guardwork.backend.membership.model.MembershipStatus;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.notification.model.NotificationAudience;
import com.guardwork.backend.notification.service.NotificationDispatcher;
import com.guardwork.backend.notification.service.NotificationIntentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Marks Applications that a Company has not updated for the configured period as INACTIVE and
 * revokes the current CV access epoch. Access is denied at read time, so a delayed worker tick can
 * never extend a stale permission.
 */
@Slf4j
@Service
public class ApplicationInactivityService {

    private static final Set<String> ACTIVE = Set.of("SUBMITTED", "UNDER_REVIEW", "SHORTLISTED", "INTERVIEWING");
    private static final String INACTIVE = "INACTIVE";

    private final JobApplicationRepository applications;
    private final ApplicationSnapshotRepository snapshots;
    private final ApplicationStatusHistoryRepository history;
    private final CandidateCvAccessGrantRepository cvGrants;
    private final CompanyRepository companies;
    private final JobPostingRepository jobs;
    private final JobAssignmentRepository assignments;
    private final CompanyMembershipRepository memberships;
    private final NotificationIntentService notifications;
    private final NotificationDispatcher dispatcher;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final Duration inactivityTtl;
    private final int batch;

    public ApplicationInactivityService(
            JobApplicationRepository applications,
            ApplicationSnapshotRepository snapshots,
            ApplicationStatusHistoryRepository history,
            CandidateCvAccessGrantRepository cvGrants,
            CompanyRepository companies,
            JobPostingRepository jobs,
            JobAssignmentRepository assignments,
            CompanyMembershipRepository memberships,
            NotificationIntentService notifications,
            NotificationDispatcher dispatcher,
            Clock clock,
            PlatformTransactionManager manager,
            @Value("${application.review.inactivity-ttl:30d}") Duration inactivityTtl,
            @Value("${application.review.inactivity-batch-size:100}") int batch) {
        if (batch < 1 || batch > 1000) {
            throw new IllegalArgumentException("Inactivity batch must be 1-1000");
        }
        this.applications = applications;
        this.snapshots = snapshots;
        this.history = history;
        this.cvGrants = cvGrants;
        this.companies = companies;
        this.jobs = jobs;
        this.assignments = assignments;
        this.memberships = memberships;
        this.notifications = notifications;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.inactivityTtl = inactivityTtl;
        this.batch = batch;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The configured review inactivity window, also used by detail responses. */
    public Duration inactivityTtl() {
        return inactivityTtl;
    }

    /** Bounded operational entry point; each Application commits independently. */
    public int expireDue() {
        Instant cutoff = clock.instant().minus(inactivityTtl);
        List<UUID> due = transaction.execute(tx ->
                applications.findInactiveApplicationIds(cutoff, batch));
        if (due == null || due.isEmpty()) return 0;

        int changed = 0;
        for (UUID applicationId : due) {
            Boolean marked = transaction.execute(tx -> markInactive(applicationId, cutoff));
            if (Boolean.TRUE.equals(marked)) changed++;
        }
        return changed;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markInactive(UUID applicationId, Instant cutoff) {
        JobApplication observed = applications.findById(applicationId).orElse(null);
        if (observed == null) return false;
        // Lock order: Company, then Job, then Application.
        var company = companies.findByIdForUpdate(observed.getCompanyId()).orElse(null);
        if (company == null) return false;
        if (jobs.lock(observed.getJobId(), observed.getCompanyId()).isEmpty()) return false;

        JobApplication application = applications.findByIdForUpdate(applicationId).orElse(null);
        if (application == null
                || !ACTIVE.contains(application.getStatus())
                || application.getStatusChangedAt() == null
                || application.getStatusChangedAt().isAfter(cutoff)) {
            return false;
        }

        Instant now = clock.instant();
        String from = application.getStatus();
        application.setStatus(INACTIVE);
        application.setStatusChangedAt(now);
        applications.flush();

        ApplicationStatusHistory event = new ApplicationStatusHistory();
        event.setApplicationId(applicationId);
        event.setFromStatus(from);
        event.setToStatus(INACTIVE);
        event.setActorUserId(null);
        event.setOccurredAt(now);
        history.saveAndFlush(event);

        cvGrants.revoke(applicationId, now, CandidateCvAccessGrant.REASON_APPLICATION_INACTIVE);

        notifyCompany(application, company, now);
        log.info("event=application_inactive application_id={} previous_status={}", applicationId, from);
        return true;
    }

    private void notifyCompany(JobApplication application, com.guardwork.backend.company.model.Company company,
            Instant now) {
        var recipients = new LinkedHashSet<Long>();
        recipients.add(company.getOwnerUserId());
        for (var assignment : assignments.findByJobIdAndEndedAtIsNullOrderById(application.getJobId())) {
            memberships.findByIdAndCompanyForUpdate(assignment.getMembershipId(), company.getId())
                    .filter(member -> member.getStatus() == MembershipStatus.ACTIVE
                            && member.getRole() == CompanyRole.RECRUITER
                            && member.getCompanyId().equals(application.getCompanyId())
                            && member.getJoinedAt().equals(assignment.getMembershipJoinedAt()))
                    .ifPresent(member -> recipients.add(member.getUserId()));
        }
        String jobTitle = snapshots.findById(application.getId())
                .map(ApplicationSnapshot::getJobTitle).orElse(null);
        for (Long recipient : recipients) {
            UUID notificationEvent = notifications.recordApplication(
                    recipient, "APPLICATION_INACTIVE", NotificationAudience.RECRUITER, company,
                    application.getJobId(), jobTitle, application.getId(), null, now);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        dispatcher.dispatch(notificationEvent);
                    }
                });
            } else {
                dispatcher.dispatch(notificationEvent);
            }
        }
    }
}
