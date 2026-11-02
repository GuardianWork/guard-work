package com.guardwork.backend.job.service;

import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.VerificationStatus;
import com.guardwork.backend.job.model.*;
import com.guardwork.backend.job.repository.JobAssignmentRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Shared content, Company and responsibility rules; callers retain authorization, locks and
 * lifecycle policy.
 */
@Component
public class JobEligibility {
  private final JobAssignmentRepository assignments;
  private final DraftContent content;
  private final Clock clock;

  public JobEligibility(JobAssignmentRepository assignments, DraftContent content, Clock clock) {
    this.assignments = assignments;
    this.content = content;
    this.clock = clock;
  }

  record Evaluation(
      boolean companyVerified,
      boolean companySuspended,
      boolean needsReassignment,
      List<DraftContent.ValidationIssue> contentIssues,
      List<DraftContent.ValidationIssue> issues) {
    boolean eligible() {
      return issues.isEmpty();
    }
  }

  Evaluation evaluate(Company company, JobPosting job, JobRevision revision) {
    return evaluate(
        company,
        job,
        revision,
        job.isOwnerResponsible() ? List.of() : assignments.current(job.getId()));
  }

  /** Reuse assignments already read for a private detail projection. */
  Evaluation evaluate(
      Company company, JobPosting job, JobRevision revision, List<JobAssignment> current) {
    boolean verified = company.getVerificationStatus() == VerificationStatus.VERIFIED;
    boolean missing = !job.isOwnerResponsible() && current.isEmpty();
    var contentIssues =
        revision == null
            ? List.<DraftContent.ValidationIssue>of()
            : List.copyOf(content.submissionIssues(revision.getContent(), clock.instant()));
    var issues = new ArrayList<>(contentIssues);
    if (!verified) issues.add(new DraftContent.ValidationIssue("company", "COMPANY_NOT_VERIFIED"));
    if (company.isBanned())
      issues.add(new DraftContent.ValidationIssue("company", "COMPANY_SUSPENDED"));
    if (missing)
      issues.add(new DraftContent.ValidationIssue("responsibility", "RESPONSIBILITY_REQUIRED"));
    return new Evaluation(
        verified, company.isBanned(), missing, contentIssues, List.copyOf(issues));
  }
}
