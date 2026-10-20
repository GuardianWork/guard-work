package com.guardwork.backend.job.service;

import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.job.dto.JobDetail;
import com.guardwork.backend.job.model.JobRevision;
import com.guardwork.backend.job.repository.*;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class JobRevisionService {
  private final JobDraftService drafts;
  private final JobRevisionRepository revisions;
  private final JobPostingRepository jobs;
  private final DraftContent content;
  private final JobExpiryService expiry;
  private final Clock clock;
  public JobRevisionService(JobDraftService drafts, JobRevisionRepository revisions, JobPostingRepository jobs,
      DraftContent content, JobExpiryService expiry, Clock clock) {
    this.drafts=drafts;this.revisions=revisions;this.jobs=jobs;this.content=content;this.expiry=expiry;this.clock=clock;
  }
  public record Derive(Long expectedJobVersion, Long sourceRevisionId) {}
  public record History(Long id, int revisionNumber, Long sourceRevisionId, String state, Long version,
      Map<String,Object> content, Long creatorUserId, Instant createdAt, Long submitterUserId, Instant submittedAt,
      Long reviewerUserId, Instant decidedAt, String rejectionReason, Long withdrawerUserId, Instant withdrawnAt) {}

  public JobDetail derive(Long actor, Long jobId, Derive request) {
    var scope=drafts.scope(actor,true);var job=drafts.visible(scope,jobId);
    if(request==null)throw DraftContent.invalid("request");
    if(request.sourceRevisionId()!=null && request.sourceRevisionId()<=0)throw DraftContent.invalid("sourceRevisionId");
    var latest=revisions.findFirstByJobIdOrderByRevisionNumberDesc(jobId).orElseThrow(JobDraftService::notFound);
    var source=request.sourceRevisionId()==null ? latest : revisions.findById(request.sourceRevisionId())
        .filter(r->r.getJobId().equals(jobId)).orElseThrow(JobDraftService::notFound);
    if(request.expectedJobVersion()==null || request.expectedJobVersion()<0)throw DraftContent.invalid("expectedJobVersion");
    if(!job.getVersion().equals(request.expectedJobVersion()))throw JobSubmissionService.conflict("40022 CONCURRENT_MODIFICATION: Reload current versions");
    expiry.reconcile(job);
    if(Set.of("CLOSED","EXPIRED").contains(job.getStatus()))throw JobSubmissionService.conflict("40978 JOB_STATE_CONFLICT: Terminal job cannot create a draft");
    if(drafts.working(job)!=null)throw JobSubmissionService.conflict("40979 WORKING_REVISION_EXISTS: Job already has a working revision");
    var revision=new JobRevision();revision.setJobId(jobId);revision.setRevisionNumber(latest.getRevisionNumber()+1);
    revision.setSourceRevisionId(source.getId());revision.setContent(source.getContent());revision.setState("DRAFT");
    revision.setCreatorUserId(actor);revision.setCreatedAt(clock.instant());revisions.saveAndFlush(revision);
    job.setVersion(job.getVersion()+1);job.setUpdatedAt(clock.instant());jobs.flush();
    drafts.record(job,revision,actor,"JOB_DRAFT_DERIVED",null,Map.of("sourceRevisionId",source.getId(),"jobVersion",job.getVersion()));
    return drafts.detail(job,revision);
  }
  public PageResponse<History> history(Long actor,Long jobId,int page,int size) {
    var scope=drafts.scope(actor,true);var job=drafts.visible(scope,jobId);
    if(page<0 || size<1 || size>100 || (long)page*size>Integer.MAX_VALUE)throw DraftContent.invalid("pagination");
    Specification<JobRevision> filter=(r,q,c)->c.equal(r.get("jobId"),job.getId());
    var result=revisions.findAll(filter,PageRequest.of(page,size,Sort.by("revisionNumber")));
    return PageResponse.of(result.stream().map(this::history).toList(),page,size,result.getTotalElements());
  }
  private History history(JobRevision r) {
    return new History(r.getId(),r.getRevisionNumber(),r.getSourceRevisionId(),r.getState(),r.getVersion(),content.decode(r.getContent()),
      r.getCreatorUserId(),r.getCreatedAt(),r.getSubmitterUserId(),r.getSubmittedAt(),r.getReviewerUserId(),r.getDecidedAt(),
      r.getRejectionReason(),r.getWithdrawerUserId(),r.getWithdrawnAt());
  }
}
