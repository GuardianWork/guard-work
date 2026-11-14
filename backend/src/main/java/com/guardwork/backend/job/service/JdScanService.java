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
  private final JobReviewQueueService reviews;
  private final JobRevisionRepository revisions;
  private final JdScanRunRepository runs;
  private final JdRevisionScanRepository links;
  private final JdScanSettings settings;
  private final ObjectMapper mapper;
  private final Clock clock;

  public JdScanService(JobDraftService drafts, JobReviewQueueService reviews,
      JobRevisionRepository revisions, JdScanRunRepository runs, JdRevisionScanRepository links,
      JdScanSettings settings, ObjectMapper mapper, Clock clock) {
    this.drafts = drafts; this.reviews = reviews; this.revisions = revisions;
    this.runs = runs; this.links = links; this.settings = settings; this.mapper = mapper; this.clock = clock;
  }

  public record Request(Long expectedRevisionVersion) {}
  public record View(Long scanId, Long revisionId, Long revisionVersion, String status,
      String mode, boolean outdated, String failureCode, Map<String, Object> report, String analysisText,
      String profile, String legalSnapshot, LocalDate asOfDate, Instant createdAt) {}

  public View request(Long actor, Long jobId, Long revisionId, Request request) {
    var scope = drafts.scope(actor, true);
    var job = drafts.visible(scope, jobId);
    var revision = revision(jobId, revisionId);
    requireEnabled();
    if (request == null || request.expectedRevisionVersion() == null || request.expectedRevisionVersion() < 0)
      throw DraftContent.invalid("expectedRevisionVersion");
    if (!revision.getVersion().equals(request.expectedRevisionVersion()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "40022 CONCURRENT_MODIFICATION: Reload current versions");
    String canonical = canonical(revision.getContent());
    JsonNode input = read(canonical);
    if (!input.path("description").isTextual() || input.path("description").asText().isBlank())
      throw DraftContent.invalid("description");
    String hash = hash(canonical);
    LocalDate date = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Ho_Chi_Minh"));
    // The existing Company lock serializes requests, including reuse across different jobs.
    var run = runs.findByCompanyIdAndInputHashAndProfileAndLegalSnapshotAndModeAndAsOfDate(
        job.getCompanyId(), hash, settings.profile(), settings.legalSnapshot(), settings.mode(), date)
        .orElseGet(() -> {
          var created = new JdScanRun();
          created.setCompanyId(job.getCompanyId()); created.setInputHash(hash);
          created.setInputContent(canonical); created.setAnalysisText(analysisText(input));
          created.setProfile(settings.profile()); created.setLegalSnapshot(settings.legalSnapshot());
          created.setMode(settings.mode()); created.setAsOfDate(date); created.setStatus("QUEUED");
          created.setCreatedAt(clock.instant()); created.setAvailableAt(clock.instant());
          return runs.saveAndFlush(created);
        });
    var association = links.findByRevisionIdAndRevisionVersionAndScanRunId(revisionId, revision.getVersion(), run.getId())
        .orElseGet(() -> {
          var link = new JdRevisionScan(); link.setCompanyId(job.getCompanyId()); link.setJobId(jobId);
          link.setRevisionId(revisionId); link.setRevisionVersion(revision.getVersion());
          link.setScanRunId(run.getId()); link.setRequestedBy(actor); link.setCreatedAt(clock.instant());
          return links.saveAndFlush(link);
        });
    return view(run, association, revision);
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
    boolean outdated = !run.getInputHash().equals(hash(canonical(revision.getContent())))
        || !run.getProfile().equals(settings.profile()) || !run.getLegalSnapshot().equals(settings.legalSnapshot())
        || !run.getMode().equals(settings.mode())
        || !run.getAsOfDate().equals(LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Ho_Chi_Minh")));
    return new View(run.getId(), revision.getId(), link.getRevisionVersion(), run.getStatus(), run.getMode(),
        outdated, run.getFailureCode(), run.getReport() == null ? null : report(run.getReport()),
        run.getAnalysisText(), run.getProfile(), run.getLegalSnapshot(), run.getAsOfDate(), run.getCreatedAt());
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
