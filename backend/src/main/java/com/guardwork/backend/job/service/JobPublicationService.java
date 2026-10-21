package com.guardwork.backend.job.service;

import com.guardwork.backend.job.model.*;
import com.guardwork.backend.job.repository.*;
import com.guardwork.backend.company.model.*;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.notification.service.*;
import com.guardwork.backend.audit.service.AuditService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class JobPublicationService {
  private final JobReviewQueueService reviews;
  private final JobPostingRepository jobs;
  private final JobRevisionRepository revisions;
  private final CompanyRepository companies;
  private final JobAssignmentRepository assignments;
  private final CompanyMembershipRepository memberships;
  private final DraftContent content;
  private final JobDraftService drafts;
  private final AuditService audit;
  private final NotificationIntentService notifications;
  private final NotificationDispatcher dispatcher;
  private final Clock clock;
  private final JobExpiryService expiry;
  private final JobEligibility eligibility;
  public JobPublicationService(JobReviewQueueService reviews, JobPostingRepository jobs, JobRevisionRepository revisions,
      CompanyRepository companies, JobAssignmentRepository assignments, CompanyMembershipRepository memberships,
      DraftContent content, JobDraftService drafts, AuditService audit, NotificationIntentService notifications,
      NotificationDispatcher dispatcher, Clock clock, JobExpiryService expiry, JobEligibility eligibility) {
    this.reviews=reviews;this.jobs=jobs;this.revisions=revisions;this.companies=companies;this.assignments=assignments;
    this.memberships=memberships;this.content=content;this.drafts=drafts;this.audit=audit;
    this.notifications=notifications;this.dispatcher=dispatcher;this.clock=clock;this.expiry=expiry;this.eligibility=eligibility;
  }
  public record Decision(Long expectedJobVersion, Long expectedRevisionVersion, String outcome, String reason) {}
  public record Receipt(Long jobId,Long revisionId,String outcome,String publicationStatus,Long jobVersion,Long revisionVersion) {}
  public record CompanyIdentity(Long id,String name) {}
  public record PublicDetail(Long id,CompanyIdentity company,Map<String,Object> content,String publicationStatus,
      Instant approvedDeadline,boolean applicationsAvailable,String applicationAvailabilityReason) {}

  public Receipt decide(Long actor,Long revisionId,Decision request,String ip,String agent) {
    reviews.requireAdmin(actor);
    var hint=revisions.findById(revisionId).filter(r->!"DRAFT".equals(r.getState())).orElseThrow(JobDraftService::notFound);
    var jobHint=jobs.findById(hint.getJobId()).orElseThrow(JobDraftService::notFound);
    var company=companies.findByIdForUpdate(jobHint.getCompanyId()).orElseThrow(JobDraftService::notFound);
    if(company.isBanned() && request!=null && "APPROVED".equals(request.outcome()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"40305 COMPANY_SUSPENDED");
    var job=jobs.lock(jobHint.getId(),company.getId()).orElseThrow(JobDraftService::notFound);
    entities.refresh(job);
    // The unlocked lookup is only a scope pointer. Refresh after acquiring the serialization lock.
    var revision=revisions.findById(revisionId).orElseThrow(JobDraftService::notFound);
    entityRefresh(revision);
    JobSubmissionService.versions(job,revision,request==null?null:new JobSubmissionService.Confirmation(request.expectedJobVersion(),request.expectedRevisionVersion(),true));
    if(!"PENDING_REVIEW".equals(revision.getState())) throw JobSubmissionService.conflict("40978 JOB_STATE_CONFLICT: Revision is not pending review");
    if(request.outcome()==null || !Set.of("APPROVED","REJECTED").contains(request.outcome())) throw DraftContent.invalid("outcome");
    String reason=request.reason()==null?null:request.reason().strip();
    if("REJECTED".equals(request.outcome()) && (reason==null || reason.codePointCount(0,reason.length())<10 || reason.codePointCount(0,reason.length())>1000)) throw DraftContent.invalid("reason");
    if("APPROVED".equals(request.outcome())) {
      var evaluation=eligibility.evaluate(company,job,revision);
      if(!evaluation.companyVerified()) throw JobSubmissionService.conflict("40982 COMPANY_VERIFICATION_REQUIRED");
      if(evaluation.needsReassignment()) throw JobSubmissionService.conflict("40980 JOB_RESPONSIBILITY_REQUIRED");
      var issues=evaluation.contentIssues();
      if(!issues.isEmpty()) throw JobSubmissionService.conflict("40981 JOB_CONTENT_INVALID: "+issues.getFirst().code());
    }
    expiry.reconcile(job);
    revision.setState(request.outcome()); revision.setReviewerUserId(actor);revision.setDecidedAt(clock.instant());
    revision.setRejectionReason("REJECTED".equals(request.outcome())?reason:null);
    revisions.flush();
    if("APPROVED".equals(request.outcome())) {
      job.setPublishedRevisionId(revisionId);job.setApprovedDeadline(OffsetDateTime.parse(content.decode(revision.getContent()).get("deadline").toString()).toInstant());
      if("DRAFT".equals(job.getStatus()))job.setStatus("PUBLISHED");
    }
    job.setVersion(job.getVersion()+1);job.setUpdatedAt(clock.instant());jobs.flush();
    drafts.record(job,revision,actor,"JOB_REVISION_"+request.outcome(),null,Map.of("jobVersion",job.getVersion(),"revisionVersion",revision.getVersion()));
    audit.recordLog(actor,"JOB_REVISION_"+request.outcome(),"JOB_REVISION",revisionId.toString(),null,"{\"outcome\":\""+request.outcome()+"\"}",reason,ip,agent);
    var recipients=new LinkedHashSet<Long>();recipients.add(company.getOwnerUserId());
    for(var assignment:assignments.current(job.getId())) memberships.findById(assignment.getMembershipId()).ifPresent(m->recipients.add(m.getUserId()));
    for(Long recipient:recipients) {
      UUID event=notifications.recordJob(recipient,"JOB_REVISION_"+request.outcome(),company,job.getId(),revisionId,clock.instant());
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){dispatcher.dispatch(event);}});
    }
    return new Receipt(job.getId(),revisionId,revision.getState(),job.getStatus(),job.getVersion(),revision.getVersion());
  }
  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entities;
  private void entityRefresh(JobRevision revision){entities.refresh(revision);}

  public PublicDetail publicDetail(Long id) {
    if(id==null || id<=0) throw JobDraftService.notFound();
    var hint=jobs.findById(id).orElseThrow(JobDraftService::notFound);
    var company=companies.findByIdForUpdate(hint.getCompanyId()).orElseThrow(JobDraftService::notFound);
    if(company.isBanned())throw JobDraftService.notFound();
    var job=jobs.lock(id,company.getId()).orElseThrow(JobDraftService::notFound);
    entities.refresh(job);
    if(job.getPublishedRevisionId()==null)throw JobDraftService.notFound();
    var revision=revisions.findById(job.getPublishedRevisionId()).filter(r->"APPROVED".equals(r.getState())).orElseThrow(JobDraftService::notFound);
    expiry.reconcile(job);
    String reason=!"PUBLISHED".equals(job.getStatus())?job.getStatus():company.getVerificationStatus()!=VerificationStatus.VERIFIED?"COMPANY_NOT_VERIFIED":null;
    return new PublicDetail(id,new CompanyIdentity(company.getId(),company.getName()),content.decode(revision.getContent()),job.getStatus(),job.getApprovedDeadline(),reason==null,reason);
  }
}
