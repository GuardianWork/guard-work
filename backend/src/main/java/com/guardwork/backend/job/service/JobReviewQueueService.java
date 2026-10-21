package com.guardwork.backend.job.service;

import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.job.model.*;
import com.guardwork.backend.job.repository.*;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.user.repository.UserRepository;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class JobReviewQueueService {
  private final UserRepository users;
  private final CompanyRepository companies;
  private final JobPostingRepository jobs;
  private final JobRevisionRepository revisions;
  private final JobEligibility eligibility;
  private final DraftContent content;
  public JobReviewQueueService(UserRepository users, CompanyRepository companies, JobPostingRepository jobs,
      JobRevisionRepository revisions, JobEligibility eligibility, DraftContent content) {
    this.users=users; this.companies=companies; this.jobs=jobs; this.revisions=revisions;
    this.eligibility=eligibility; this.content=content;
  }
  public record Review(Long id, Long jobId, Long companyId, int revisionNumber, String state,
      Long jobVersion, Long revisionVersion, Map<String,Object> content, Long submitterUserId, Instant submittedAt,
      Long reviewerUserId, Instant decidedAt, String rejectionReason, Long withdrawerUserId, Instant withdrawnAt,
      boolean companyVerified, boolean companySuspended, boolean needsReassignment,
      boolean approvalEligible, List<DraftContent.ValidationIssue> validationIssues) {}
  public void requireAdmin(Long actor) {
    var user=users.findByIdForUpdate(actor).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,"40100 UNAUTHORIZED"));
    if (!"ACTIVE".equals(user.getStatus())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"40304 ACCOUNT_SUSPENDED");
    if (!"ADMIN".equals(user.getRole())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"40300 FORBIDDEN: Platform Administrator required");
  }
  public PageResponse<Review> queue(Long actor, String status, int page, int size) {
    requireAdmin(actor); paging(page,size);
    if (!Set.of("PENDING_REVIEW","APPROVED","REJECTED","WITHDRAWN","ALL").contains(status)) throw DraftContent.invalid("status");
    Specification<JobRevision> filter=(r,q,c)-> status.equals("ALL") ? c.notEqual(r.get("state"),"DRAFT") : c.equal(r.get("state"),status);
    var result=revisions.findAll(filter,PageRequest.of(page,size,Sort.by("submittedAt","id")));
    return PageResponse.of(result.stream().map(this::review).toList(),page,size,result.getTotalElements());
  }
  public Review inspect(Long actor, Long id) {
    requireAdmin(actor);
    return review(revisions.findById(id).filter(r->!"DRAFT".equals(r.getState())).orElseThrow(JobDraftService::notFound));
  }
  public PageResponse<Review> history(Long actor, Long jobId, int page, int size) {
    requireAdmin(actor); paging(page,size);
    if (!jobs.existsById(jobId)) throw JobDraftService.notFound();
    Specification<JobRevision> filter=(r,q,c)->c.and(c.equal(r.get("jobId"),jobId),c.notEqual(r.get("state"),"DRAFT"));
    var result=revisions.findAll(filter,PageRequest.of(page,size,Sort.by("revisionNumber")));
    return PageResponse.of(result.stream().map(this::review).toList(),page,size,result.getTotalElements());
  }
  private Review review(JobRevision r) {
    var job=jobs.findById(r.getJobId()).orElseThrow(JobDraftService::notFound);
    var company=companies.findById(job.getCompanyId()).orElseThrow(JobDraftService::notFound);
    var evaluation=eligibility.evaluate(company,job,r);
    return new Review(r.getId(),job.getId(),job.getCompanyId(),r.getRevisionNumber(),r.getState(),job.getVersion(),r.getVersion(),
      content.decode(r.getContent()),r.getSubmitterUserId(),r.getSubmittedAt(),r.getReviewerUserId(),r.getDecidedAt(),r.getRejectionReason(),
      r.getWithdrawerUserId(),r.getWithdrawnAt(),evaluation.companyVerified(),evaluation.companySuspended(),evaluation.needsReassignment(),
      "PENDING_REVIEW".equals(r.getState())&&evaluation.eligible(),evaluation.issues());
  }
  private void paging(int page,int size) {
    if(page<0 || size<1 || size>100 || (long)page*size>Integer.MAX_VALUE) throw DraftContent.invalid("pagination");
  }
}
