package com.guardwork.backend.job.service;

import com.guardwork.backend.company.model.VerificationStatus;
import com.guardwork.backend.job.dto.JobDetail;
import com.guardwork.backend.job.repository.JobPostingRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class JobLifecycleService {
 private final JobDraftService drafts;
 private final JobPostingRepository jobs;
 private final JobExpiryService expiry;
 private final Clock clock;
 public JobLifecycleService(JobDraftService drafts,JobPostingRepository jobs,JobExpiryService expiry,Clock clock) {
  this.drafts=drafts;this.jobs=jobs;this.expiry=expiry;this.clock=clock;
 }
 public record Request(Long expectedJobVersion) {}
 public JobDetail change(Long actor,Long id,String action,Request request) {
  var scope=drafts.scope(actor,true);var job=drafts.visible(scope,id);
  if(request==null || request.expectedJobVersion()==null || request.expectedJobVersion()<0)throw DraftContent.invalid("expectedJobVersion");
  if(!job.getVersion().equals(request.expectedJobVersion()))throw JobSubmissionService.conflict("40022 CONCURRENT_MODIFICATION: Reload current versions");
  expiry.reconcile(job);
  String previous=job.getStatus();String next;
  switch(action) {
   case "pause" -> {if(!"PUBLISHED".equals(previous))throw state();next="PAUSED";}
   case "resume" -> {
    if(!"PAUSED".equals(previous))throw state();
    if(scope.company().getVerificationStatus()!=VerificationStatus.VERIFIED)throw JobSubmissionService.conflict("40982 COMPANY_VERIFICATION_REQUIRED: Company must be verified");
    if(job.getApprovedDeadline()==null || !clock.instant().isBefore(job.getApprovedDeadline()))throw JobSubmissionService.conflict("40981 JOB_DEADLINE_PASSED: Approved deadline must be future");
    next="PUBLISHED";
   }
   case "close" -> {if(!Set.of("PUBLISHED","PAUSED").contains(previous))throw state();next="CLOSED";job.setClosedAt(clock.instant());}
   default -> throw DraftContent.invalid("action");
  }
  job.setStatus(next);job.setUpdatedAt(clock.instant());jobs.flush();
  drafts.record(job,null,actor,"JOB_"+switch(action){case "pause"->"PAUSED";case "resume"->"RESUMED";default->"CLOSED";},null,
    Map.of("previousStatus",previous,"status",next,"jobVersion",job.getVersion()));
  return drafts.detail(job,drafts.working(job),scope);
 }
 private static ResponseStatusException state(){return JobSubmissionService.conflict("40978 JOB_STATE_CONFLICT: Invalid lifecycle transition");}
}
