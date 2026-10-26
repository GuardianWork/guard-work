package com.guardwork.backend.job.service;

import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.job.dto.*;
import com.guardwork.backend.job.model.*;
import com.guardwork.backend.job.repository.*;
import com.guardwork.backend.membership.model.CompanyMembership;
import com.guardwork.backend.membership.model.CompanyRole;
import com.guardwork.backend.membership.model.MembershipStatus;
import com.guardwork.backend.membership.service.CompanyAccessService;
import com.guardwork.backend.notification.service.*;
import com.guardwork.backend.onboarding.service.OnboardingAccessPolicy;
import com.guardwork.backend.user.model.User;
import jakarta.persistence.criteria.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class JobDraftService {
  private final CompanyAccessService access;
  private final OnboardingAccessPolicy onboarding;
  private final JobPostingRepository jobs;
  private final JobRevisionRepository revisions;
  private final JobAssignmentRepository assignments;
  private final JobAuditEventRepository audit;
  private final DraftContent content;
  private final NotificationIntentService notifications;
  private final NotificationDispatcher dispatcher;
  private final Clock clock;
  private final JobExpiryService expiry;
  private final JobEligibility eligibility;

  public JobDraftService(CompanyAccessService access, OnboardingAccessPolicy onboarding,
      JobPostingRepository jobs, JobRevisionRepository revisions, JobAssignmentRepository assignments,
      JobAuditEventRepository audit, DraftContent content, NotificationIntentService notifications,
      NotificationDispatcher dispatcher, Clock clock, JobExpiryService expiry, JobEligibility eligibility) {
    this.access = access; this.onboarding = onboarding; this.jobs = jobs; this.revisions = revisions;
    this.assignments = assignments; this.audit = audit; this.content = content;
    this.eligibility=eligibility; this.expiry=expiry; this.notifications = notifications; this.dispatcher = dispatcher; this.clock = clock;
  }

  public JobDetail create(Long actor, JobRequests.Create request) {
    var scope = scope(actor, false);
    if (request == null) throw DraftContent.invalid("request");
    var normalized = content.normalize(request.content());
    var now = clock.instant();
    var job = new JobPosting();
    job.setCompanyId(scope.company().getId()); job.setCreatorUserId(actor);
    job.setStatus("DRAFT"); job.setOwnerResponsible(scope.membership().getRole() == CompanyRole.OWNER);
    job.setCreatedAt(now); job.setUpdatedAt(now);
    jobs.saveAndFlush(job);
    var revision = new JobRevision();
    revision.setJobId(job.getId()); revision.setRevisionNumber(1); revision.setContent(normalized);
    revision.setState("DRAFT"); revision.setCreatorUserId(actor); revision.setCreatedAt(now);
    revisions.saveAndFlush(revision);
    record(job, revision, actor, "JOB_CREATED", null, Map.of("ownerResponsible", job.isOwnerResponsible()));
    if (!job.isOwnerResponsible()) {
      var assignment = new JobAssignment();
      assignment.setJobId(job.getId()); assignment.setCompanyId(job.getCompanyId());
      assignment.setMembershipId(scope.membership().getId());
      assignment.setMembershipJoinedAt(scope.membership().getJoinedAt());
      assignment.setAssignedByUserId(actor); assignment.setAssignedAt(now);
      assignments.saveAndFlush(assignment);
      record(job, revision, actor, "JOB_ASSIGNED", scope.membership().getId(), Map.of("assignmentId", assignment.getId()));
      UUID event = notifications.recordJobAssignment(actor, scope.company(), job.getId(), revision.getId(), now);
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { dispatcher.dispatch(event); }
      });
    }
    return detail(job, revision, scope);
  }

  public JobDetail inspect(Long actor, Long id) {
    var scope = scope(actor, true);
    var job = visible(scope, id);
    expiry.reconcile(job);
    return detail(job, working(job), scope);
  }

  public JobDetail save(Long actor, Long id, JobRequests.Save request) {
    var scope = scope(actor, true);
    var job = visible(scope, id);
    var revision = working(job);
    if (request == null || request.expectedJobVersion() == null || request.expectedRevisionVersion() == null
        || request.expectedJobVersion() < 0 || request.expectedRevisionVersion() < 0)
      throw DraftContent.invalid("versions");
    if (!job.getVersion().equals(request.expectedJobVersion()) || revision == null
        || !revision.getVersion().equals(request.expectedRevisionVersion()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "40022 CONCURRENT_MODIFICATION: Reload current versions");
    expiry.reconcile(job);
    if (!"DRAFT".equals(revision.getState()) || Set.of("CLOSED", "EXPIRED").contains(job.getStatus()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "40978 JOB_STATE_CONFLICT: Draft cannot be edited");
    revision.setContent(content.normalize(request.content()));
    // Every accepted save advances both tokens, including an identical replacement.
    revision.setVersion(revision.getVersion() + 1);
    job.setVersion(job.getVersion() + 1);
    job.setUpdatedAt(clock.instant());
    revisions.flush(); jobs.flush();
    record(job, revision, actor, "JOB_DRAFT_SAVED", null, Map.of("jobVersion", job.getVersion(), "revisionVersion", revision.getVersion()));
    return detail(job, revision, scope);
  }

  public PageResponse<JobDetail> list(Long actor, int page, int size, String status, Boolean needsReassignment) {
    var scope = scope(actor, false);
    if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
      throw DraftContent.invalid("pagination");
    if (status != null && !Set.of("DRAFT", "PUBLISHED", "PAUSED", "CLOSED", "EXPIRED").contains(status))
      throw DraftContent.invalid("status");
    var listedAt=clock.instant();
    Specification<JobPosting> filter = (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      predicates.add(cb.equal(root.get("companyId"), scope.company().getId()));
      if (status != null) {
        Predicate deadlinePassed=cb.and(root.get("status").in("PUBLISHED","PAUSED"),
            cb.lessThanOrEqualTo(root.<Instant>get("approvedDeadline"),listedAt));
        predicates.add("EXPIRED".equals(status)
            ? cb.or(cb.equal(root.get("status"),"EXPIRED"),deadlinePassed)
            : cb.and(cb.equal(root.get("status"),status),cb.not(deadlinePassed)));
      }
      if (scope.membership().getRole() == CompanyRole.RECRUITER)
        predicates.add(cb.exists(currentAssignment(query, cb, root, scope.membership().getId())));
      if (needsReassignment != null) {
        Predicate responsible = cb.or(cb.isTrue(root.get("ownerResponsible")), cb.exists(currentAssignment(query, cb, root, null)));
        predicates.add(needsReassignment ? cb.not(responsible) : responsible);
      }
      return cb.and(predicates.toArray(Predicate[]::new));
    };
    var result = jobs.findAll(filter, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    return PageResponse.of(result.stream().map(j -> detail(j, working(j), scope)).toList(), page, size, result.getTotalElements());
  }

  private Subquery<Long> currentAssignment(CriteriaQuery<?> query,
      CriteriaBuilder cb, Root<JobPosting> job, Long memberId) {
    var sub = query.subquery(Long.class);
    var a = sub.from(JobAssignment.class); var m = sub.from(CompanyMembership.class);
    var u = sub.from(User.class);
    var predicates = new ArrayList<Predicate>();
    predicates.add(cb.equal(a.get("jobId"), job.get("id")));
    predicates.add(cb.isNull(a.get("endedAt")));
    predicates.add(cb.equal(a.get("membershipId"), m.get("id")));
    predicates.add(cb.equal(a.get("companyId"), m.get("companyId")));
    predicates.add(cb.equal(a.get("membershipJoinedAt"), m.get("joinedAt")));
    predicates.add(cb.equal(m.get("status"), MembershipStatus.ACTIVE));
    predicates.add(cb.equal(m.get("role"), CompanyRole.RECRUITER));
    predicates.add(cb.equal(m.get("userId"), u.get("id")));
    predicates.add(cb.equal(u.get("status"), "ACTIVE"));
    if (memberId != null) predicates.add(cb.equal(m.get("id"), memberId));
    return sub.select(a.get("id")).where(predicates.toArray(Predicate[]::new));
  }

  CompanyAccessService.Access scope(Long actor, boolean concealed) {
    CompanyAccessService.Access scope;
    try { scope = access.findCurrentCompanyAccess(actor); }
    catch (ResponseStatusException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) throw notFound();
      throw e;
    }
    if (scope == null) {
      if (concealed) throw notFound();
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "40302 NOT_COMPANY_MEMBER: Active Company membership required");
    }
    if (scope.membership().getRole() == CompanyRole.ADMIN)
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "40303 INSUFFICIENT_COMPANY_ROLE: Owner or Recruiter required");
    if (!onboarding.permits(actor, "/api/companies/me/jobs", "POST"))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "40330 PERSONAL_VERIFICATION_REQUIRED: Personal verification required");
    return scope;
  }

  JobPosting visible(CompanyAccessService.Access scope, Long id) {
    if (id == null || id <= 0) throw DraftContent.invalid("jobId");
    var job = jobs.lock(id, scope.company().getId()).orElseThrow(JobDraftService::notFound);
    if (scope.membership().getRole() == CompanyRole.RECRUITER
        && assignments.current(job.getId()).stream().noneMatch(a -> a.getMembershipId().equals(scope.membership().getId())
            && a.getMembershipJoinedAt().equals(scope.membership().getJoinedAt()))) throw notFound();
    return job;
  }

  JobRevision working(JobPosting job) {
    return revisions.findByJobIdAndStateIn(job.getId(), List.of("DRAFT", "PENDING_REVIEW")).orElse(null);
  }

  JobDetail detail(JobPosting job, JobRevision revision, CompanyAccessService.Access scope) {
    var current = assignments.current(job.getId());
    String status=expiry.effectiveStatus(job);
    boolean terminal=Set.of("CLOSED","EXPIRED").contains(status);
    var actions=new ArrayList<String>();
    var evaluation=eligibility.evaluate(scope.company(),job,revision,current);
    boolean responsible=!evaluation.needsReassignment();
    boolean verified=evaluation.companyVerified();
    if(revision==null && !terminal) actions.add("CREATE_DRAFT");
    if(revision!=null && "DRAFT".equals(revision.getState())) {
      actions.add("PREVIEW");
      if(!terminal) {
        actions.add("SAVE_DRAFT");
        if(evaluation.eligible())actions.add("SUBMIT");
      }
    }
    if(revision!=null && "PENDING_REVIEW".equals(revision.getState()))actions.add("WITHDRAW");
    if("PUBLISHED".equals(status)) {actions.add("PAUSE");actions.add("CLOSE");}
    if("PAUSED".equals(status)) {if(verified)actions.add("RESUME");actions.add("CLOSE");}
    return new JobDetail(job.getId(), job.getCompanyId(), job.getCreatorUserId(), status, job.getVersion(),
        job.isOwnerResponsible(), !responsible, JobDetail.Revision.from(revision, revision == null ? null : content.decode(revision.getContent())),
        job.getPublishedRevisionId(), job.getApprovedDeadline(), current.stream().map(JobDetail.Assignment::from).toList(),
        actions, job.getCreatedAt(), job.getUpdatedAt());
  }

  void record(JobPosting job, JobRevision revision, Long actor, String event, Long member, Map<String, Object> facts) {
    var entry = new JobAuditEvent();
    entry.setCompanyId(job.getCompanyId()); entry.setJobId(job.getId()); entry.setRevisionId(revision==null ? null : revision.getId());
    entry.setActorUserId(actor); entry.setEventType(event); entry.setMembershipId(member);
    entry.setCreatedAt(clock.instant()); entry.setFacts(facts); audit.saveAndFlush(entry);
  }

  public static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "40478 JOB_NOT_FOUND: Job not found");
  }
}
