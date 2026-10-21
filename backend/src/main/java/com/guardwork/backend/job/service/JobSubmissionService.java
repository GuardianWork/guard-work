package com.guardwork.backend.job.service;

import com.guardwork.backend.job.dto.*;
import com.guardwork.backend.job.model.*;
import com.guardwork.backend.job.repository.*;
import com.guardwork.backend.company.model.VerificationStatus;
import com.guardwork.backend.membership.service.CompanyAccessService;
import java.time.Clock;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class JobSubmissionService {
  private final JobDraftService drafts;
  private final JobRevisionRepository revisions;
  private final JobPostingRepository jobs;
  private final JobAssignmentRepository assignments;
  private final DraftContent content;
  private final Clock clock;
  private final JobExpiryService expiry;
  public JobSubmissionService(JobDraftService drafts, JobRevisionRepository revisions,
      JobPostingRepository jobs, JobAssignmentRepository assignments, DraftContent content, Clock clock, JobExpiryService expiry) {
    this.drafts = drafts; this.revisions = revisions; this.jobs = jobs;
    this.expiry=expiry; this.assignments = assignments; this.content = content; this.clock = clock;
  }
  public record Preview(Map<String,Object> content, Long jobVersion, Long revisionVersion,
      boolean submissionEligible, List<DraftContent.ValidationIssue> validationIssues) {}
  public record Confirmation(Long expectedJobVersion, Long expectedRevisionVersion, Boolean confirmed) {}

  public Preview preview(Long actor, Long id) {
    var scope = drafts.scope(actor, true); var job = drafts.visible(scope, id); var revision = drafts.working(job);
    if (revision == null || !"DRAFT".equals(revision.getState())) throw conflict("40978 JOB_STATE_CONFLICT: No editable draft");
    var issues = eligibility(scope, job, revision);
    return new Preview(content.decode(revision.getContent()), job.getVersion(), revision.getVersion(), issues.isEmpty(), issues);
  }
  public JobDetail submit(Long actor, Long id, Long revisionId, Confirmation request) {
    var scope = drafts.scope(actor, true); var job = drafts.visible(scope, id); var revision = revision(job, revisionId);
    versions(job, revision, request);
    if (!Boolean.TRUE.equals(request.confirmed())) throw DraftContent.invalid("confirmed");
    expiry.reconcile(job);
    if (!"DRAFT".equals(revision.getState()) || Set.of("CLOSED", "EXPIRED").contains(job.getStatus()))
      throw conflict("40978 JOB_STATE_CONFLICT: Revision cannot be submitted");
    var issues = eligibility(scope, job, revision);
    for (var issue : issues) {
      if (issue.code().equals("COMPANY_NOT_VERIFIED")) throw conflict("40982 COMPANY_VERIFICATION_REQUIRED: Company must be verified");
      if (issue.code().equals("RESPONSIBILITY_REQUIRED")) throw conflict("40980 JOB_RESPONSIBILITY_REQUIRED: Responsible person required");
      if (issue.code().equals("DEADLINE_PASSED")) throw conflict("40981 JOB_DEADLINE_PASSED: Future deadline required");
    }
    if (!issues.isEmpty()) throw DraftContent.invalid(issues.getFirst().field());
    revision.setState("PENDING_REVIEW"); revision.setSubmitterUserId(actor); revision.setSubmittedAt(clock.instant());
    advance(job); revisions.flush();
    drafts.record(job, revision, actor, "JOB_REVISION_SUBMITTED", null, Map.of("jobVersion", job.getVersion(), "revisionVersion", revision.getVersion()));
    return drafts.detail(job, revision, scope);
  }
  public JobDetail withdraw(Long actor, Long id, Long revisionId, Confirmation request) {
    var scope = drafts.scope(actor, true); var job = drafts.visible(scope, id); var revision = revision(job, revisionId);
    versions(job, revision, request);
    expiry.reconcile(job);
    if (!"PENDING_REVIEW".equals(revision.getState())) throw conflict("40978 JOB_STATE_CONFLICT: Revision is not pending review");
    revision.setState("WITHDRAWN"); revision.setWithdrawerUserId(actor); revision.setWithdrawnAt(clock.instant());
    advance(job); revisions.flush();
    drafts.record(job, revision, actor, "JOB_REVISION_WITHDRAWN", null, Map.of("jobVersion", job.getVersion(), "revisionVersion", revision.getVersion()));
    return drafts.detail(job, null, scope);
  }
  private List<DraftContent.ValidationIssue> eligibility(CompanyAccessService.Access scope, JobPosting job, JobRevision revision) {
    var issues = new ArrayList<>(content.submissionIssues(revision.getContent(), clock.instant()));
    if (scope.company().getVerificationStatus() != VerificationStatus.VERIFIED)
      issues.add(new DraftContent.ValidationIssue("company", "COMPANY_NOT_VERIFIED"));
    if (!job.isOwnerResponsible() && assignments.current(job.getId()).isEmpty())
      issues.add(new DraftContent.ValidationIssue("responsibility", "RESPONSIBILITY_REQUIRED"));
    if (Set.of("CLOSED", "EXPIRED").contains(expiry.effectiveStatus(job)))
      issues.add(new DraftContent.ValidationIssue("job", "TERMINAL_STATE"));
    return issues;
  }
  private JobRevision revision(JobPosting job, Long id) {
    return revisions.findById(id).filter(r -> r.getJobId().equals(job.getId())).orElseThrow(JobDraftService::notFound);
  }
  public static void versions(JobPosting job, JobRevision revision, Confirmation request) {
    if (request == null || request.expectedJobVersion() == null || request.expectedRevisionVersion() == null
        || request.expectedJobVersion() < 0 || request.expectedRevisionVersion() < 0) throw DraftContent.invalid("versions");
    if (!job.getVersion().equals(request.expectedJobVersion()) || !revision.getVersion().equals(request.expectedRevisionVersion()))
      throw conflict("40022 CONCURRENT_MODIFICATION: Reload current versions");
  }
  private void advance(JobPosting job) { job.setVersion(job.getVersion()+1); job.setUpdatedAt(clock.instant()); jobs.flush(); }
  public static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
