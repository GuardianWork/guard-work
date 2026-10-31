package com.guardwork.backend.job.service;

import com.guardwork.backend.audit.service.AuditService;
import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.company.model.VerificationStatus;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.job.model.JobAuditEvent;
import com.guardwork.backend.job.repository.JobAuditEventRepository;
import com.guardwork.backend.job.repository.JobPostingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Staff sanctions and event inspection share the existing job authority and audit boundaries. */
@Service
@Transactional
public class StaffJobManagementService {
  private final JobReviewQueueService reviews;
  private final CompanyRepository companies;
  private final JobPostingRepository jobs;
  private final JobAuditEventRepository events;
  private final JobDraftService drafts;
  private final AuditService audit;
  private final Clock clock;
  @PersistenceContext private EntityManager entities;

  public StaffJobManagementService(
      JobReviewQueueService reviews,
      CompanyRepository companies,
      JobPostingRepository jobs,
      JobAuditEventRepository events,
      JobDraftService drafts,
      AuditService audit,
      Clock clock) {
    this.reviews = reviews;
    this.companies = companies;
    this.jobs = jobs;
    this.events = events;
    this.drafts = drafts;
    this.audit = audit;
    this.clock = clock;
  }

  public record HideRequest(Long expectedJobVersion, String reason) {}

  public record HideReceipt(
      Long jobId, String publicationStatus, Long jobVersion,
      Long actorUserId, Instant suspendedAt, String reason) {}

  public record HistoryEvent(
      Long id, Long jobId, Long revisionId, Long membershipId, Long actorUserId,
      String eventType, Instant createdAt, String previousStatus, String status, String reason) {}

  public HideReceipt hide(Long actor, Long jobId, HideRequest request, String ip, String agent) {
    reviews.requireStaff(actor);
    if (request == null || request.expectedJobVersion() == null || request.expectedJobVersion() < 0)
      throw DraftContent.invalid("expectedJobVersion");
    String reason = request.reason() == null ? null : request.reason().strip();
    if (reason == null || reason.isEmpty() || reason.codePointCount(0, reason.length()) > 1000)
      throw DraftContent.invalid("reason");
    var hint = jobs.findById(jobId).orElseThrow(JobDraftService::notFound);
    var company = companies.findByIdForUpdate(hint.getCompanyId()).orElseThrow(JobDraftService::notFound);
    var job = jobs.lock(jobId, company.getId()).orElseThrow(JobDraftService::notFound);
    // The first lookup only identifies the Company; decisions use the locked current snapshot.
    entities.refresh(job);
    if (!job.getVersion().equals(request.expectedJobVersion()))
      throw JobSubmissionService.conflict("40022 CONCURRENT_MODIFICATION: Reload current versions");
    Instant now = clock.instant();
    if (!"PUBLISHED".equals(job.getStatus()) || job.getPublishedRevisionId() == null
        || job.getApprovedDeadline() == null || !now.isBefore(job.getApprovedDeadline())
        || company.isBanned() || company.getVerificationStatus() != VerificationStatus.VERIFIED)
      throw JobSubmissionService.conflict("40978 JOB_STATE_CONFLICT: Job is not public");
    job.setStatus("SUSPENDED");
    job.setUpdatedAt(now);
    jobs.flush();
    drafts.record(job, null, actor, "JOB_SUSPENDED", null,
        Map.of("previousStatus", "PUBLISHED", "status", "SUSPENDED",
            "reason", reason, "jobVersion", job.getVersion()));
    audit.recordLog(actor, "JOB_SUSPENDED", "JOB", jobId.toString(),
        "{\"status\":\"PUBLISHED\"}", "{\"status\":\"SUSPENDED\"}", reason, ip, agent);
    return new HideReceipt(jobId, job.getStatus(), job.getVersion(), actor, now, reason);
  }

  public PageResponse<HistoryEvent> history(Long actor, Long jobId, int page, int size) {
    reviews.requireStaff(actor);
    if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
      throw DraftContent.invalid("pagination");
    if (!jobs.existsById(jobId)) throw JobDraftService.notFound();
    var result = events.findByJobId(jobId,
        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    return PageResponse.of(result.stream().map(this::project).toList(), page, size, result.getTotalElements());
  }

  private HistoryEvent project(JobAuditEvent event) {
    var facts = event.getFacts();
    return new HistoryEvent(event.getId(), event.getJobId(), event.getRevisionId(),
        event.getMembershipId(), event.getActorUserId(), event.getEventType(), event.getCreatedAt(),
        text(facts, "previousStatus"), text(facts, "status"), text(facts, "reason"));
  }

  private static String text(Map<String, Object> facts, String key) {
    return facts != null && facts.get(key) instanceof String value ? value : null;
  }
}
