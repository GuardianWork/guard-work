package com.guardwork.backend.job.service;

import com.fasterxml.jackson.databind.*;
import com.guardwork.backend.job.model.*;
import com.guardwork.backend.job.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class JdScanService {
  private final JobDraftService drafts;
  private final JobContentVisibility visibility;
  private final com.guardwork.backend.membership.repository.CompanyMembershipRepository memberships;
  private final com.guardwork.backend.membership.service.CompanyAccessService access;
  private final JobReviewQueueService reviews;
  private final JobRevisionRepository revisions;
  private final JdScanRunRepository runs;
  private final JdRevisionScanRepository links;
  private final JdScanSettings settings;
  private final ObjectMapper mapper;
  private final Clock clock;

  public JdScanService(JobDraftService drafts, JobReviewQueueService reviews,
      JobRevisionRepository revisions, JdScanRunRepository runs, JdRevisionScanRepository links,
      JdScanSettings settings, ObjectMapper mapper, Clock clock, JobContentVisibility visibility,
      com.guardwork.backend.membership.service.CompanyAccessService access,
      com.guardwork.backend.membership.repository.CompanyMembershipRepository memberships) {
    this.memberships = memberships;
    this.visibility = visibility; this.access = access;
    this.drafts = drafts; this.reviews = reviews; this.revisions = revisions;
    this.runs = runs; this.links = links; this.settings = settings; this.mapper = mapper; this.clock = clock;
  }

  public record Request(Long expectedRevisionVersion) {}
  public record View(Long scanId, Long revisionId, Long revisionVersion, String status,
      String mode, boolean outdated, String failureCode, Map<String, Object> report, String analysisText,
      String profile, String legalSnapshot, LocalDate asOfDate, Instant createdAt) {}

  public record PublicView(String mode, boolean outdated, LocalDate asOfDate, Instant completedAt,
      String analysisText, Map<String, Object> report) {}

  /** Called only after the publication service has established public job visibility. */
  public PublicView publishedAnalysis(Long companyId, JobRevision revision) {
    return runs.latestCompletedForRevision(companyId, revision.getJobId(), revision.getId(),
        hash(canonical(revision.getContent())))
        .map(run -> new PublicView(run.getMode(), outdated(run, revision), run.getAsOfDate(),
            run.getFinishedAt(), run.getAnalysisText(), publicReport(run.getReport())))
        .orElse(null);
  }

  private Map<String, Object> publicReport(String json) {
    JsonNode source = read(json);
    var result = select(source, "schemaVersion", "outcome", "warnings");
    var findings = new ArrayList<Map<String, Object>>();
    for (JsonNode finding : source.path("findings")) {
      var item = select(finding, "start", "end", "spanText", "riskType", "severity", "verdict", "explanation");
      var citations = new ArrayList<Map<String, Object>>();
      for (JsonNode citation : finding.path("citations"))
        citations.add(select(citation, "nodeId", "label", "documentNumber", "quote", "quoteGrounded",
            "effectiveStatus", "applicability"));
      item.put("citations", citations);
      findings.add(item);
    }
    result.put("findings", findings);
    return result;
  }

  private Map<String, Object> select(JsonNode source, String... fields) {
    var result = new LinkedHashMap<String, Object>();
    for (String field : fields)
      if (source.has(field)) result.put(field, mapper.convertValue(source.get(field), Object.class));
    return result;
  }

  public View request(Long actor, Long jobId, Long revisionId, Request request) {
    var scope = drafts.scope(actor, true);
    var job = drafts.visible(scope, jobId);
    var revision = revision(jobId, revisionId);
    return requestForRevision(actor, job, revision, request);
  }

  private View requestForRevision(Long actor, JobPosting job, JobRevision revision, Request request) {
    checkVersion(revision, request == null ? null : request.expectedRevisionVersion());
    String canonical = canonical(revision.getContent());
    JsonNode input = read(canonical);
    if (!input.path("description").isTextual() || input.path("description").asText().isBlank())
      throw DraftContent.invalid("description");
    String hash = hash(canonical);
    LocalDate date = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Ho_Chi_Minh"));
    // The existing Company lock serializes requests, including reuse across different jobs.
    var run = runs.reusableForContent(job.getCompanyId(), hash)
        .orElseGet(() -> {
          requireEnabled();
          var created = new JdScanRun();
          created.setCompanyId(job.getCompanyId()); created.setInputHash(hash);
          created.setInputContent(canonical); created.setAnalysisText(analysisText(input));
          created.setProfile(settings.profile()); created.setLegalSnapshot(settings.legalSnapshot());
          created.setMode(settings.mode()); created.setAsOfDate(date); created.setStatus("QUEUED");
          created.setCreatedAt(clock.instant()); created.setAvailableAt(clock.instant());
          return runs.saveAndFlush(created);
        });
    Long revisionId = revision.getId();
    Long jobId = job.getId();
    var association = links.findByRevisionIdAndRevisionVersionAndScanRunId(revisionId, revision.getVersion(), run.getId())
        .orElseGet(() -> {
          var link = new JdRevisionScan(); link.setCompanyId(job.getCompanyId()); link.setJobId(jobId);
          link.setRevisionId(revisionId); link.setRevisionVersion(revision.getVersion());
          link.setScanRunId(run.getId()); link.setRequestedBy(actor); link.setCreatedAt(clock.instant());
          return links.saveAndFlush(link);
        });
    return view(run, association, revision);
  }

  /** Minimal projection shared by authenticated readers, independent of requester role. */
  public record Result(Long revisionId, Long revisionVersion, String status, String mode,
      boolean outdated, LocalDate asOfDate, Instant completedAt, String analysisText,
      Map<String, Object> report) {}

  private JobContentVisibility.Locked readable(Long actor, Long jobId, Long revisionId) {
    var visible = visibility.lock(jobId, memberships.findActiveCompanyId(actor).orElse(null));
    var user = access.requireActiveUser(actor);
    revision(jobId, revisionId);
    // Public visibility grants access only to the published revision, never a newer draft.
    if (revisionId.equals(visible.job().getPublishedRevisionId())
        && !visible.company().isBanned() && !visible.job().isStaffSuspended()
        && !"SUSPENDED".equals(visible.job().getStatus())) {
      visibility.publishedRevision(visible);
      return visible;
    }
    if ("STAFF".equals(user.getRole())) {
      reviews.inspect(actor, revisionId);
    } else {
      // Do not acquire another Company's lock for an unrelated membership.
      if (memberships.findActiveCompanyId(actor).filter(visible.company().getId()::equals).isEmpty())
        throw JobDraftService.notFound();
      drafts.visible(drafts.scope(actor, true), jobId);
    }
    return visible;
  }

  public Result requestShared(Long actor, Long jobId, Long revisionId, Request request) {
    var visible = readable(actor, jobId, revisionId);
    var revision = revision(jobId, revisionId);
    var requested = requestForRevision(actor, visible.job(), revision, request);
    return result(runs.findById(requested.scanId()).orElseThrow(JobDraftService::notFound), revision);
  }

  public Result latestShared(Long actor, Long jobId, Long revisionId, Long expectedRevisionVersion) {
    var visible = readable(actor, jobId, revisionId);
    var revision = revision(jobId, revisionId);
    checkVersion(revision, expectedRevisionVersion);
    return runs.reusableForContent(visible.company().getId(),
        hash(canonical(revision.getContent()))).map(run -> result(run, revision)).orElse(null);
  }

  private Result result(JdScanRun run, JobRevision revision) {
    boolean completed = "COMPLETED".equals(run.getStatus());
    return new Result(revision.getId(), revision.getVersion(), run.getStatus(), run.getMode(),
        outdated(run, revision), run.getAsOfDate(), completed ? run.getFinishedAt() : null,
        completed ? run.getAnalysisText() : null, completed ? publicReport(run.getReport()) : null);
  }

  private void checkVersion(JobRevision revision, Long expectedRevisionVersion) {
    if (expectedRevisionVersion == null || expectedRevisionVersion < 0)
      throw DraftContent.invalid("expectedRevisionVersion");
    if (!revision.getVersion().equals(expectedRevisionVersion))
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "40022 CONCURRENT_MODIFICATION: Reload current versions");
  }

  public View inspect(Long actor, Long jobId, Long revisionId, Long scanId) {
    var scope = drafts.scope(actor, true);
    drafts.visible(scope, jobId);
    return associated(revision(jobId, revisionId), scanId);
  }

  public View staffInspect(Long actor, Long revisionId, Long scanId) {
    // Existing Staff inspection conceals drafts and rechecks live platform authority.
    var review = reviews.inspect(actor, revisionId);
    var revision = revision(review.jobId(), revisionId);
    var run = runs.findById(scanId).orElseThrow(JobDraftService::notFound);
    // A submitted revision must not expose an earlier, removed private draft snapshot.
    if (!run.getInputHash().equals(hash(canonical(revision.getContent())))) throw JobDraftService.notFound();
    return associated(revision, scanId);
  }

  public View latest(Long actor, Long jobId, Long revisionId, Long expectedRevisionVersion) {
    var scope = drafts.scope(actor, true);
    var job = drafts.visible(scope, jobId);
    return latestForRevision(job.getCompanyId(), revision(jobId, revisionId), expectedRevisionVersion);
  }

  public View staffLatest(Long actor, Long revisionId, Long expectedRevisionVersion) {
    var review = reviews.inspect(actor, revisionId);
    return latestForRevision(review.companyId(), revision(review.jobId(), revisionId), expectedRevisionVersion);
  }

  private View latestForRevision(Long companyId, JobRevision revision, Long expectedRevisionVersion) {
    if (expectedRevisionVersion == null || expectedRevisionVersion < 0)
      throw DraftContent.invalid("expectedRevisionVersion");
    if (!revision.getVersion().equals(expectedRevisionVersion))
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "40022 CONCURRENT_MODIFICATION: Reload current versions");
    // A state-only version bump may retain the same content. Removed draft text never matches.
    return links.latestForContent(companyId, revision.getJobId(), revision.getId(),
        hash(canonical(revision.getContent())))
        .map(link -> view(runs.findById(link.getScanRunId()).orElseThrow(JobDraftService::notFound), link, revision))
        .orElse(null);
  }

  private View associated(JobRevision revision, Long scanId) {
    var link = links.findFirstByRevisionIdAndScanRunIdOrderByIdDesc(revision.getId(), scanId)
        .orElseThrow(JobDraftService::notFound);
    return view(runs.findById(scanId).orElseThrow(JobDraftService::notFound), link, revision);
  }

  private JobRevision revision(Long jobId, Long revisionId) {
    if (revisionId == null || revisionId <= 0) throw DraftContent.invalid("revisionId");
    return revisions.findById(revisionId).filter(r -> r.getJobId().equals(jobId))
        .orElseThrow(JobDraftService::notFound);
  }

  private View view(JdScanRun run, JdRevisionScan link, JobRevision revision) {
    return new View(run.getId(), revision.getId(), link.getRevisionVersion(), run.getStatus(), run.getMode(),
        outdated(run, revision), run.getFailureCode(), run.getReport() == null ? null : report(run.getReport()),
        run.getAnalysisText(), run.getProfile(), run.getLegalSnapshot(), run.getAsOfDate(), run.getCreatedAt());
  }

  private boolean outdated(JdScanRun run, JobRevision revision) {
    return !run.getInputHash().equals(hash(canonical(revision.getContent())))
        || !run.getProfile().equals(settings.profile()) || !run.getLegalSnapshot().equals(settings.legalSnapshot())
        || !run.getMode().equals(settings.mode())
        || !run.getAsOfDate().equals(LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Ho_Chi_Minh")));
  }

  private void requireEnabled() {
    if (!settings.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "50319 JD_SCAN_DISABLED: JD analysis is disabled");
  }

  private String canonical(String json) {
    try {
      Map<?, ?> input = mapper.readerFor(Map.class)
          .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(json);
      return mapper.writeValueAsString(sorted(input));
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot read retained JD content", failure);
    }
  }

  private Object sorted(Object value) {
    if (value instanceof Map<?, ?> map) {
      var result = new TreeMap<String, Object>();
      map.forEach((key, item) -> result.put(key.toString(), sorted(item)));
      return result;
    }
    if (value instanceof List<?> list) return list.stream().map(this::sorted).toList();
    return value;
  }

  private String analysisText(JsonNode input) {
    var text = new StringBuilder();
    input.fields().forEachRemaining(field -> text.append(field.getKey()).append(":\n")
        .append(field.getValue().isTextual() ? field.getValue().asText() : field.getValue().toString()).append("\n\n"));
    return text.toString();
  }

  private JsonNode read(String json) {
    try { return mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(json); }
    catch (Exception failure) { throw new IllegalStateException("Cannot decode JD snapshot", failure); }
  }

  private Map<String, Object> report(String json) {
    try { return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {}); }
    catch (Exception failure) { throw new IllegalStateException("Cannot decode JD report", failure); }
  }

  private String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
  }
}
