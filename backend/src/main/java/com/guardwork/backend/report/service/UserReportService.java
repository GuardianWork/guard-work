package com.guardwork.backend.report.service;

import com.guardwork.backend.audit.service.AuditService;
import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.company.model.VerificationStatus;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.candidate.CandidateProfileRepository;
import com.guardwork.backend.job.repository.JobPostingRepository;
import com.guardwork.backend.job.repository.JobRevisionRepository;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.staff.repository.AccountBanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.report.dto.ReportResponse.*;
import com.guardwork.backend.report.model.UserReport;
import com.guardwork.backend.report.repository.UserReportRepository;
import com.guardwork.backend.report.repository.ReportEvidenceRepository;
import com.guardwork.backend.report.model.ReportEvidence;
import org.springframework.web.multipart.MultipartFile;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class UserReportService {
  private final UserReportRepository reports;
  private final UserRepository users;
  private final CompanyRepository companies;
  private final AuditService audit;
  private final Clock clock;
  private final ReportEvidenceRepository evidence;
  private final CandidateProfileRepository candidates;
  private final JobPostingRepository jobs;
  private final JobRevisionRepository revisions;
  private final ObjectMapper json;
  private final AccountBanRepository bans;

  public UserReportService(UserReportRepository reports, UserRepository users,
      CompanyRepository companies, AuditService audit, Clock clock, ReportEvidenceRepository evidence,
      CandidateProfileRepository candidates, JobPostingRepository jobs, JobRevisionRepository revisions,
      ObjectMapper json, AccountBanRepository bans) {
    this.reports = reports;
    this.users = users;
    this.companies = companies;
    this.audit = audit;
    this.clock = clock;
    this.evidence = evidence;
    this.candidates = candidates;
    this.jobs = jobs;
    this.revisions = revisions;
    this.json = json;
    this.bans = bans;
  }

  public Receipt submit(Long actor, Map<String, Object> input, List<MultipartFile> files, String ip, String agent) {
    requireActor(actor, "USER", true);
    if (input == null || !input.keySet().equals(Set.of("targetType", "targetId", "complaint"))
        || !(input.get("targetType") instanceof String type)
        || !(input.get("targetId") instanceof String targetId)
        || !(input.get("complaint") instanceof String text)) throw invalid();
    if (!Set.of("COMPANY", "JOB", "CANDIDATE").contains(type)) throw invalid();
    if ("CANDIDATE".equals(type)) candidateId(targetId);
    else positiveId(targetId);
    String complaint = text.strip();
    int length = complaint.codePointCount(0, complaint.length());
    if (length < 10 || length > 5000) throw invalid();
    if (!target(type, targetId).available()) throw error(HttpStatus.NOT_FOUND, "REPORT_TARGET_UNAVAILABLE");
    var attachments = ReportEvidenceFiles.validate(files);
    UserReport report = new UserReport();
    report.setReporterId(actor);
    report.setTargetType(type);
    report.setTargetId(targetId);
    report.setComplaint(complaint);
    report.setStatus("OPEN");
    report.setCreatedAt(clock.instant());
    reports.saveAndFlush(report);
    for (var attachment : attachments) {
      attachment.setReportId(report.getId());
      evidence.save(attachment);
    }
    audit.recordLog(actor, "REPORT_SUBMITTED", "REPORT", report.getId().toString(),
        null, auditTarget(report), null, ip, agent);
    return receipt(report);
  }

  @Transactional(readOnly = true)
  public PageResponse<Summary> search(Long actor, String status, String type, int page, int size) {
    requireActor(actor, "STAFF", false);
    if (!Set.of("ALL", "OPEN", "IN_REVIEW", "RESOLVED", "REJECTED").contains(status)
        || !Set.of("ALL", "COMPANY", "JOB", "CANDIDATE").contains(type)
        || page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) throw invalid();
    Specification<UserReport> filter = (root, query, cb) -> cb.and(
        "ALL".equals(status) ? cb.conjunction() : cb.equal(root.get("status"), status),
        "ALL".equals(type) ? cb.conjunction() : cb.equal(root.get("targetType"), type));
    var results = reports.findAll(filter,
        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    return PageResponse.of(results, results.stream().map(this::summary).toList());
  }

  @Transactional(readOnly = true)
  public Detail detail(Long actor, Long id) {
    requireActor(actor, "STAFF", false);
    UserReport report = find(id);
    Target target = target(report.getTargetType(), report.getTargetId());
    String name = users.findById(report.getReporterId()).map(User::fullName).orElse(null);
    return new Detail(report.getId(), report.getTargetType(), report.getTargetId(), report.getStatus(),
        report.getCreatedAt(), report.getComplaint(), target.label(), target.available(),
        new Reporter(report.getReporterId(), name), report.getProcessingOutcome(),
        evidence.findMetadataByReportId(id).stream().map(file -> new Evidence(file.getId(), file.getFilename(),
            file.getContentType(), file.getSizeBytes(), file.getAvailable(),
            file.getAvailable() ? "/api/staff/reports/" + id + "/evidence/" + file.getId() : null)).toList());
  }

  @Transactional(readOnly = true)
  public ReportEvidence download(Long actor, Long reportId, UUID evidenceId) {
    requireActor(actor, "STAFF", false);
    if (reportId == null || reportId <= 0) throw invalid();
    return evidence.findByIdAndReportId(evidenceId, reportId).filter(file -> file.getContent() != null)
        .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "REPORT_EVIDENCE_NOT_FOUND"));
  }

  private UserReport find(Long id) {
    if (id == null || id <= 0) throw invalid();
    return reports.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND"));
  }

  private Receipt receipt(UserReport report) {
    return new Receipt(report.getId(), report.getTargetType(), report.getTargetId(),
        report.getStatus(), report.getCreatedAt());
  }

  private Summary summary(UserReport report) {
    Target target = target(report.getTargetType(), report.getTargetId());
    return new Summary(report.getId(), report.getTargetType(), report.getTargetId(), report.getStatus(),
        report.getCreatedAt(), report.getComplaint(), target.label(), target.available());
  }

  private record Target(String label, boolean available) {}
  private Target target(String type, String id) {
    if ("JOB".equals(type)) {
      // Match public-detail visibility without reconciling expiry or mutating a target during Staff reads.
      var job = jobs.findById(positiveId(id)).orElse(null);
      if (job == null || job.isStaffSuspended() || "SUSPENDED".equals(job.getStatus())
          || job.getPublishedRevisionId() == null) return new Target(null, false);
      var company = companies.findById(job.getCompanyId()).orElse(null);
      if (company == null || companyBanned(company)) return new Target(null, false);
      return revisions.findById(job.getPublishedRevisionId()).filter(revision -> "APPROVED".equals(revision.getState()))
          .map(revision -> {
            try {
              var title = json.readTree(revision.getContent()).path("title");
              return new Target(title.isTextual() ? title.asText() : null, true);
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
              throw new IllegalStateException("Invalid retained job content", failure);
            }
          }).orElse(new Target(null, false));
    }
    if ("CANDIDATE".equals(type)) {
      return candidates.findById(candidateId(id)).filter(profile -> profile.isPublic())
          .flatMap(profile -> users.findById(profile.getUserId())).filter(this::activeTargetUser)
          .map(user -> new Target(user.fullName(), true)).orElse(new Target(null, false));
    }
    if ("COMPANY".equals(type)) {
      return companies.findById(positiveId(id))
          .filter(c -> !companyBanned(c) && c.getVerificationStatus() == VerificationStatus.VERIFIED)
          .map(c -> new Target(c.getName(), true)).orElse(new Target(null, false));
    }
    return new Target(null, false);
  }

  private boolean companyBanned(Company company) {
    return company.isBanned() && !bans.findByCompanyIdAndEndedAtIsNull(company.getId())
        .map(ban -> !ban.activeAt(clock.instant())).orElse(false);
  }

  private boolean activeTargetUser(User user) {
    boolean active = "ACTIVE".equals(user.getStatus()) || ("BANNED".equals(user.getStatus())
        && bans.findByUserIdAndEndedAtIsNull(user.getId()).map(ban -> !ban.activeAt(clock.instant())).orElse(false));
    return active && "ACTIVE".equals(user.getSelfServiceState());
  }

  private String auditTarget(UserReport report) {
    try {
      return json.writeValueAsString(Map.of("targetType", report.getTargetType(), "targetId", report.getTargetId()));
    } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
      throw new IllegalStateException("Cannot encode report audit reference", failure);
    }
  }

  private User requireActor(Long id, String role, boolean lock) {
    User user = (lock ? users.findByIdForUpdate(id) : users.findById(id))
        .orElseThrow(() -> error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"));
    if (!user.allowsNormalAccess() || !role.equals(user.getRole()))
      throw error(HttpStatus.FORBIDDEN, "FORBIDDEN");
    return user;
  }

  private static long positiveId(String id) {
    try {
      long value = Long.parseLong(id);
      if (value <= 0 || !Long.toString(value).equals(id)) throw invalid();
      return value;
    } catch (NumberFormatException failure) { throw invalid(); }
  }

  private static UUID candidateId(String id) {
    try {
      UUID value = UUID.fromString(id);
      if (!value.toString().equals(id)) throw invalid();
      return value;
    } catch (IllegalArgumentException failure) { throw invalid(); }
  }

  public static ResponseStatusException invalid() { return error(HttpStatus.BAD_REQUEST, "INVALID_REPORT_INPUT"); }
  private static ResponseStatusException error(HttpStatus status, String reason) {
    return new ResponseStatusException(status, reason);
  }
}
