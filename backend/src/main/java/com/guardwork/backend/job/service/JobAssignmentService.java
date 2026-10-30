package com.guardwork.backend.job.service;

import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.job.dto.JobDetail;
import com.guardwork.backend.job.model.*;
import com.guardwork.backend.job.repository.*;
import com.guardwork.backend.membership.model.*;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.membership.service.CompanyAccessService;
import com.guardwork.backend.notification.service.*;
import com.guardwork.backend.user.repository.UserRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class JobAssignmentService {
  private final CompanyAccessService access;
  private final JobPostingRepository jobs;
  private final JobAssignmentRepository assignments;
  private final CompanyMembershipRepository members;
  private final UserRepository users;
  private final JobAuditEventRepository audit;
  private final NotificationIntentService notifications;
  private final NotificationDispatcher dispatcher;
  private final JobDraftService drafts;
  private final Clock clock;

  public JobAssignmentService(
      CompanyAccessService access,
      JobPostingRepository jobs,
      JobAssignmentRepository assignments,
      CompanyMembershipRepository members,
      UserRepository users,
      JobAuditEventRepository audit,
      NotificationIntentService notifications,
      NotificationDispatcher dispatcher,
      JobDraftService drafts,
      Clock clock) {
    this.access = access;
    this.jobs = jobs;
    this.assignments = assignments;
    this.members = members;
    this.users = users;
    this.audit = audit;
    this.notifications = notifications;
    this.dispatcher = dispatcher;
    this.drafts = drafts;
    this.clock = clock;
  }

  public JobDetail assign(Long actor, Long id, Long memberId, Long version, boolean enabled) {
    var company = owner(actor);
    var job = job(company, id);
    checkVersion(job, version);
    var member =
        members
            .findByIdAndCompanyForUpdate(memberId, company.getId())
            .orElseThrow(JobDraftService::notFound);
    var existing = assignments.findByJobIdAndMembershipIdAndEndedAtIsNull(id, memberId);
    if (enabled) {
      if (member.getStatus() != MembershipStatus.ACTIVE
          || member.getRole() != CompanyRole.RECRUITER
          || users
              .findById(member.getUserId())
              .filter(u -> "ACTIVE".equals(u.getStatus()))
              .isEmpty()) throw JobDraftService.notFound();
      if (existing.stream().anyMatch(a -> a.getMembershipJoinedAt().equals(member.getJoinedAt())))
        return drafts.inspect(actor, id);
      // A reused membership row must never revive an earlier period.
      for (var old : existing) end(company, job, old, member, actor, "MEMBERSHIP_ENDED");
      var assignment = new JobAssignment();
      assignment.setCompanyId(company.getId());
      assignment.setJobId(id);
      assignment.setMembershipId(memberId);
      assignment.setMembershipJoinedAt(member.getJoinedAt());
      assignment.setAssignedAt(clock.instant());
      assignment.setAssignedByUserId(actor);
      assignments.saveAndFlush(assignment);
      changed(job);
      record(job, actor, "JOB_ASSIGNED", memberId);
      notify(company, job, member.getUserId(), "JOB_ASSIGNED");
    } else {
      if (existing.isEmpty()) return drafts.inspect(actor, id);
      boolean before = responsible(job);
      for (var assignment : existing) end(company, job, assignment, member, actor, "UNASSIGNED");
      changed(job);
      lost(company, job, before, actor);
    }
    return drafts.inspect(actor, id);
  }

  public JobDetail responsibility(Long actor, Long id, Long version, Boolean responsible) {
    var company = owner(actor);
    var job = job(company, id);
    checkVersion(job, version);
    if (responsible == null) throw DraftContent.invalid("responsible");
    if (job.isOwnerResponsible() != responsible) {
      boolean before = responsible(job);
      job.setOwnerResponsible(responsible);
      changed(job);
      record(job, actor, "OWNER_RESPONSIBILITY_CHANGED", null);
      lost(company, job, before, actor);
    }
    return drafts.inspect(actor, id);
  }

  /** Caller already holds actor, Company and departing membership locks. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void endMembership(Company company, CompanyMembership member, Long actor, String reason) {
    for (var assignment :
        assignments.findByMembershipIdAndEndedAtIsNullOrderByJobId(member.getId())) {
      var job = job(company, assignment.getJobId());
      boolean before = responsible(job);
      end(company, job, assignment, member, actor, reason);
      changed(job);
      lost(company, job, before, actor);
    }
  }

  private Company owner(Long actor) {
    return access.requireOwner(actor).company();
  }

  private JobPosting job(Company company, Long id) {
    if (id == null || id <= 0) throw DraftContent.invalid("jobId");
    return jobs.lock(id, company.getId()).orElseThrow(JobDraftService::notFound);
  }

  private void checkVersion(JobPosting job, Long version) {
    if (version == null || version < 0) throw DraftContent.invalid("expectedJobVersion");
    if (!version.equals(job.getVersion()))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "40022 CONCURRENT_MODIFICATION: Reload current versions");
  }

  private boolean responsible(JobPosting job) {
    return job.isOwnerResponsible() || !assignments.current(job.getId()).isEmpty();
  }

  private void changed(JobPosting job) {
    job.setVersion(job.getVersion() + 1);
    job.setUpdatedAt(clock.instant());
    jobs.flush();
  }

  private void end(
      Company company,
      JobPosting job,
      JobAssignment assignment,
      CompanyMembership member,
      Long actor,
      String reason) {
    assignment.setEndedAt(clock.instant());
    assignment.setEndedByUserId(actor);
    assignment.setEndReason(reason);
    assignments.flush();
    record(job, actor, "JOB_UNASSIGNED", member.getId());
    notify(company, job, member.getUserId(), "JOB_UNASSIGNED");
  }

  private void lost(Company company, JobPosting job, boolean before, Long actor) {
    if (before && !responsible(job)) {
      record(job, actor, "JOB_NEEDS_REASSIGNMENT", null);
      notify(company, job, company.getOwnerUserId(), "JOB_NEEDS_REASSIGNMENT");
    }
  }

  private void record(JobPosting job, Long actor, String type, Long member) {
    var event = new JobAuditEvent();
    event.setCompanyId(job.getCompanyId());
    event.setJobId(job.getId());
    event.setActorUserId(actor);
    event.setEventType(type);
    event.setMembershipId(member);
    event.setCreatedAt(clock.instant());
    event.setFacts(
        Map.of("ownerResponsible", job.isOwnerResponsible(), "jobVersion", job.getVersion()));
    audit.saveAndFlush(event);
  }

  private void notify(Company company, JobPosting job, Long recipient, String type) {
    UUID event =
        notifications.recordJob(recipient, type, company, job.getId(), null, clock.instant());
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            dispatcher.dispatch(event);
          }
        });
  }
}
