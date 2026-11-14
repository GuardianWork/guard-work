package com.guardwork.backend.application.service;

import com.guardwork.backend.application.dto.ApplicationDetailResponse;
import com.guardwork.backend.application.dto.ApplicationStatusChangeRequest;
import com.guardwork.backend.application.dto.ApplicationStatusHistoryResponse;
import com.guardwork.backend.application.dto.ApplicationWithdrawalRequest;
import com.guardwork.backend.application.model.ApplicationSnapshot;
import com.guardwork.backend.application.model.ApplicationStatusHistory;
import com.guardwork.backend.application.model.JobApplication;
import com.guardwork.backend.application.repository.ApplicationSnapshotRepository;
import com.guardwork.backend.application.repository.ApplicationStatusHistoryRepository;
import com.guardwork.backend.application.repository.JobApplicationRepository;
import com.guardwork.backend.candidate.CandidateProfileRepository;
import com.guardwork.backend.common.exception.AppException;
import com.guardwork.backend.common.exception.ErrorCode;
import com.guardwork.backend.company.model.VerificationStatus;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.job.repository.JobAssignmentRepository;
import com.guardwork.backend.job.repository.JobPostingRepository;
import com.guardwork.backend.membership.model.CompanyRole;
import com.guardwork.backend.membership.model.MembershipStatus;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.membership.service.CompanyAccessService;
import com.guardwork.backend.notification.model.NotificationAudience;
import com.guardwork.backend.notification.service.NotificationDispatcher;
import com.guardwork.backend.notification.service.NotificationIntentService;
import com.guardwork.backend.user.repository.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApplicationWorkflowService {
    private static final Set<String> ACTIVE = Set.of("SUBMITTED", "UNDER_REVIEW", "SHORTLISTED", "INTERVIEWING");
    private static final Set<String> REASONS = Set.of(
            "QUALIFICATIONS_MISMATCH", "EXPERIENCE_MISMATCH", "POSITION_FILLED", "OTHER");

    private final CompanyAccessService companyAccess;
    private final JobPostingRepository jobs;
    private final CompanyRepository companies;
    private final JobAssignmentRepository assignments;
    private final CompanyMembershipRepository memberships;
    private final JobApplicationRepository applications;
    private final ApplicationSnapshotRepository snapshots;
    private final JobApplicationService applicationDetails;
    private final ApplicationStatusHistoryRepository history;
    private final CandidateProfileRepository profiles;
    private final UserRepository users;
    private final NotificationIntentService notifications;
    private final NotificationDispatcher dispatcher;
    private final Clock clock;

    public ApplicationWorkflowService(
            CompanyAccessService companyAccess,
            JobPostingRepository jobs,
            CompanyRepository companies,
            JobAssignmentRepository assignments,
            CompanyMembershipRepository memberships,
            JobApplicationRepository applications,
            ApplicationSnapshotRepository snapshots,
            JobApplicationService applicationDetails,
            ApplicationStatusHistoryRepository history,
            CandidateProfileRepository profiles,
            UserRepository users,
            NotificationIntentService notifications,
            NotificationDispatcher dispatcher,
            Clock clock) {
        this.companyAccess = companyAccess;
        this.jobs = jobs;
        this.companies = companies;
        this.assignments = assignments;
        this.memberships = memberships;
        this.applications = applications;
        this.snapshots = snapshots;
        this.applicationDetails = applicationDetails;
        this.history = history;
        this.profiles = profiles;
        this.users = users;
        this.notifications = notifications;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    @Transactional
    public ApplicationDetailResponse changeStatus(Long actorId, Long jobId, UUID applicationId,
            ApplicationStatusChangeRequest request) {
        var access = companyAccess.findCurrentCompanyAccess(actorId);
        if (access == null) throw forbidden("40302 NOT_COMPANY_MEMBER: Active Company membership required");
        if (access.membership().getRole() == CompanyRole.ADMIN
                || (access.membership().getRole() == CompanyRole.OWNER
                    && !actorId.equals(access.company().getOwnerUserId()))
                || (access.membership().getRole() != CompanyRole.OWNER
                    && access.membership().getRole() != CompanyRole.RECRUITER)) {
            throw forbidden("40303 INSUFFICIENT_COMPANY_ROLE: Owner or Recruiter required");
        }
        if (access.company().getVerificationStatus() != VerificationStatus.VERIFIED) {
            throw forbidden("40305 COMPANY_NOT_VERIFIED: Company must be verified");
        }
        var job = jobs.lock(jobId, access.company().getId()).orElseThrow(ApplicationWorkflowService::notFound);
        if (job.isStaffSuspended() || "SUSPENDED".equals(job.getStatus())) throw notFound();
        if (access.membership().getRole() == CompanyRole.RECRUITER
                && assignments.current(jobId).stream().noneMatch(assignment ->
                    assignment.getCompanyId().equals(access.company().getId())
                            && assignment.getMembershipId().equals(access.membership().getId())
                            && assignment.getMembershipJoinedAt().equals(access.membership().getJoinedAt()))) {
            throw notFound();
        }
        JobApplication application = applications.findByIdForUpdate(applicationId)
                .filter(item -> item.getCompanyId().equals(access.company().getId()) && item.getJobId().equals(jobId))
                .orElseThrow(ApplicationWorkflowService::notFound);
        validateRequest(request);
        if (request.version().longValue() != application.getVersion().longValue()) throw versionConflict();
        String target = request.status();
        String reason = normalized(request.rejectionReason());
        String explanation = normalized(request.internalExplanation());
        String feedback = normalized(request.candidateFeedback());

        if ("REJECTED".equals(target) && "REJECTED".equals(application.getStatus())) {
            ApplicationStatusHistory old = history.findByApplicationIdOrderById(applicationId).stream()
                    .filter(item -> "REJECTED".equals(item.getToStatus())).reduce((a, b) -> b).orElseThrow();
            if (!java.util.Objects.equals(reason, old.getRejectionReason())
                    || !java.util.Objects.equals(explanation, old.getInternalExplanation())
                    || !java.util.Objects.equals(feedback, old.getCandidateFeedback())) {
                throw invalid("Repeated rejection cannot change its original details");
            }
            return applicationDetails.companyDetail(actorId, jobId, applicationId);
        }
        if (target.equals(application.getStatus())) {
            if (reason != null || explanation != null || feedback != null) throw invalid("Rejection details require REJECTED");
            return applicationDetails.companyDetail(actorId, jobId, applicationId);
        }
        String from = application.getStatus();
        if (!ACTIVE.contains(from)) throw invalid("Application is terminal");
        if (!"REJECTED".equals(target)
                && !("UNDER_REVIEW".equals(target) && "SUBMITTED".equals(from))
                && !("SHORTLISTED".equals(target) && ("SUBMITTED".equals(from) || "UNDER_REVIEW".equals(from)))
                && !("INTERVIEWING".equals(target) && ("UNDER_REVIEW".equals(from) || "SHORTLISTED".equals(from)))
                && !("HIRED".equals(target)
                    && Set.of("UNDER_REVIEW", "SHORTLISTED", "INTERVIEWING").contains(from))) {
            throw invalid("Unsupported Application status transition");
        }
        if ("REJECTED".equals(target)) {
            if (!REASONS.contains(reason)) throw invalid("rejectionReason");
            if ("OTHER".equals(reason) && (explanation == null || explanation.isBlank())) {
                throw invalid("internalExplanation");
            }
        } else if (reason != null || explanation != null || feedback != null) {
            throw invalid("Rejection details require REJECTED");
        }
        var now = clock.instant();
        application.setStatus(target);
        applications.flush();
        ApplicationStatusHistory event = new ApplicationStatusHistory();
        event.setApplicationId(applicationId);
        event.setFromStatus(from);
        event.setToStatus(target);
        event.setActorUserId(actorId);
        event.setOccurredAt(now);
        event.setRejectionReason(reason);
        event.setInternalExplanation(explanation);
        event.setCandidateFeedback(feedback);
        history.saveAndFlush(event);
        Long candidateUserId = profiles.findById(application.getCandidateId())
                .orElseThrow(ApplicationWorkflowService::notFound).getUserId();
        String jobTitle = snapshots.findById(applicationId)
                .map(ApplicationSnapshot::getJobTitle).orElse(null);
        String type = "REJECTED".equals(target) ? "APPLICATION_REJECTED"
                : "INTERVIEWING".equals(target) ? "APPLICATION_INTERVIEWING"
                : "HIRED".equals(target) ? "APPLICATION_HIRED" : "APPLICATION_STATUS_CHANGED";
        UUID notificationEvent =
            notifications.recordApplication(candidateUserId, type, NotificationAudience.CANDIDATE, access.company(), jobId,
                jobTitle, applicationId, feedback, now);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatcher.dispatch(notificationEvent);
            }
        });
        return applicationDetails.companyDetail(actorId, jobId, applicationId);
    }

    @Transactional
    public ApplicationDetailResponse withdraw(Long candidateUserId, UUID applicationId,
            ApplicationWithdrawalRequest request) {
        var candidate = users.findByIdForUpdate(candidateUserId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        if (!candidate.allowsNormalAccess()) throw new AppException(ErrorCode.ACCOUNT_SUSPENDED);
        var profile = profiles.findByUserId(candidateUserId).orElseThrow(ApplicationWorkflowService::notFound);
        var observed = applications.findRouteById(applicationId)
                .filter(item -> item.getCandidateId().equals(profile.getId()))
                .orElseThrow(ApplicationWorkflowService::notFound);
        var company = companies.findByIdForUpdate(observed.getCompanyId())
                .orElseThrow(ApplicationWorkflowService::notFound);
        var job = jobs.lock(observed.getJobId(), observed.getCompanyId())
                .orElseThrow(ApplicationWorkflowService::notFound);
        // AD-003/AR-001: a suspended Job Posting conceals its Applications; withdrawal is denied.
        if (job.isStaffSuspended() || "SUSPENDED".equals(job.getStatus())) throw notFound();
        JobApplication application = applications.findByIdForUpdate(applicationId)
                .filter(item -> item.getCandidateId().equals(profile.getId())
                        && item.getCompanyId().equals(company.getId())
                        && item.getJobId().equals(observed.getJobId()))
                .orElseThrow(ApplicationWorkflowService::notFound);
        if (request == null || request.version() == null || request.version() < 0) throw invalid("version");
        if (request.version().longValue() != application.getVersion().longValue()) throw versionConflict();
        if ("WITHDRAWN".equals(application.getStatus())) {
            return applicationDetails.candidateDetail(candidateUserId, applicationId);
        }
        if (!ACTIVE.contains(application.getStatus())) throw invalid("Application is terminal");
        String from = application.getStatus();
        application.setStatus("WITHDRAWN");
        applications.flush();
        ApplicationStatusHistory event = new ApplicationStatusHistory();
        event.setApplicationId(applicationId);
        event.setFromStatus(from);
        event.setToStatus("WITHDRAWN");
        event.setActorUserId(candidateUserId);
        event.setOccurredAt(clock.instant());
        history.saveAndFlush(event);

        var recipients = new java.util.LinkedHashSet<Long>();
        recipients.add(company.getOwnerUserId());
        for (var assignment : assignments.findByJobIdAndEndedAtIsNullOrderById(application.getJobId())) {
            memberships.findByIdAndCompanyForUpdate(assignment.getMembershipId(), company.getId()).filter(member ->
                    member.getStatus() == MembershipStatus.ACTIVE
                            && member.getRole() == CompanyRole.RECRUITER
                            && member.getCompanyId().equals(application.getCompanyId())
                            && member.getJoinedAt().equals(assignment.getMembershipJoinedAt()))
                    .ifPresent(member -> recipients.add(member.getUserId()));
        }
        String jobTitle = snapshots.findById(applicationId)
                .map(ApplicationSnapshot::getJobTitle).orElse(null);
        for (Long recipient : recipients) {
            UUID notificationEvent = notifications.recordApplication(recipient, "APPLICATION_WITHDRAWN", NotificationAudience.RECRUITER, company,
                    application.getJobId(), jobTitle, applicationId, null, clock.instant());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatcher.dispatch(notificationEvent);
                }
            });
        }
        return applicationDetails.candidateDetail(candidateUserId, applicationId);
    }

    private static void validateRequest(ApplicationStatusChangeRequest request) {
        if (request == null || request.status() == null || request.version() == null || request.version() < 0) {
            throw invalid("request");
        }
        if (!Set.of("SUBMITTED", "UNDER_REVIEW", "SHORTLISTED", "INTERVIEWING", "HIRED", "REJECTED")
                .contains(request.status())) {
            throw invalid("status");
        }
        if ("HIRED".equals(request.status())) {
            if (!Boolean.TRUE.equals(request.hiringConfirmed())) throw invalid("hiringConfirmed");
        } else if (request.hiringConfirmed() != null) {
            throw invalid("hiringConfirmed");
        }
        for (String text : List.of(request.internalExplanation() == null ? "" : request.internalExplanation(),
                request.candidateFeedback() == null ? "" : request.candidateFeedback())) {
            if (text.strip().codePointCount(0, text.strip().length()) > 1000) throw invalid("text length");
        }
    }

    private static String normalized(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static AppException invalid(String field) {
        return new AppException(ErrorCode.INVALID_INPUT, "Invalid " + field);
    }

    private static AppException versionConflict() {
        return new AppException(ErrorCode.APPLICATION_VERSION_CONFLICT);
    }

    private static ResponseStatusException forbidden(String reason) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "40400 APPLICATION_NOT_FOUND: Application not found");
    }
}
