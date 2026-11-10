package com.guardwork.backend.job.service;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

/** The local Python HTTP service is the external boundary, never a browser-facing API. */
@Component
public class JdScanAdapter {
  private final JdScanSettings settings;
  private final ObjectMapper mapper;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public JdScanAdapter(JdScanSettings settings, ObjectMapper mapper) {
    this.settings = settings; this.mapper = mapper;
    URI url = URI.create(settings.adapterUrl());
    if (!"http".equals(url.getScheme()) || !Set.of("127.0.0.1", "localhost", "[::1]").contains(url.getHost())
        || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
        || !(url.getPath().isEmpty() || url.getPath().equals("/")))
      throw new IllegalArgumentException("JD adapter must use a loopback HTTP origin");
  }

  public static class Failure extends RuntimeException {
    private final boolean retryable;
    public Failure(boolean retryable) { super("JD adapter operation failed"); this.retryable = retryable; }
    public boolean retryable() { return retryable; }
  }

  public String analyze(JdScanQueue.Claim claim) {
    try {
      String body = mapper.writeValueAsString(Map.of("analysisText", claim.analysisText(),
          "mode", claim.mode(), "profile", claim.profile(), "legalSnapshot", claim.legalSnapshot(),
          "asOfDate", claim.asOfDate().toString()));
      var request = HttpRequest.newBuilder(URI.create(settings.adapterUrl().replaceAll("/$", "") + "/analyze"))
          .timeout(Duration.ofSeconds(660)).header("Content-Type", "application/json")
          .header("X-GuardWork-AI-Key", settings.sharedKey()).POST(HttpRequest.BodyPublishers.ofString(body)).build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200)
        throw new Failure(response.statusCode() == 429 || response.statusCode() >= 500);
      if (response.body().length > 524288) throw new Failure(false);
      JsonNode report = mapper.readTree(response.body());
      validate(report, claim);
      return mapper.writeValueAsString(report);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt(); throw new Failure(true);
    } catch (com.fasterxml.jackson.core.JacksonException failure) {
      throw new Failure(false);
    } catch (IOException failure) {
      throw new Failure(true);
    }
  }

  private void validate(JsonNode report, JdScanQueue.Claim claim) {
    if (report == null || !report.isObject() || !report.path("schemaVersion").asText().equals("1")
        || !claim.mode().equals(report.path("mode").asText())
        || !claim.profile().equals(report.path("profile").asText())
        || !claim.legalSnapshot().equals(report.path("legalSnapshot").asText())
        || !claim.asOfDate().toString().equals(report.path("asOfDate").asText())
        || !Set.of("POTENTIAL_RISKS", "NEEDS_REVIEW", "NO_RISKS_DETECTED", "OUT_OF_SCOPE")
            .contains(report.path("outcome").asText())
        || !report.path("findings").isArray() || report.path("findings").size() > 100
        || !report.path("warnings").isArray() || !report.path("usage").path("available").isBoolean())
      throw new Failure(false);
    if (claim.mode().equals("stub") && (!report.path("outcome").asText().equals("NEEDS_REVIEW")
        || !report.path("findings").isEmpty())) throw new Failure(false);
    int length = claim.analysisText().codePointCount(0, claim.analysisText().length());
    boolean needsReview = false;
    boolean risk = false;
    for (JsonNode finding : report.path("findings")) {
      JsonNode startNode = finding.path("start"), endNode = finding.path("end");
      int start = startNode.asInt(-1), end = endNode.asInt(-1);
      String verdict = finding.path("verdict").asText();
      if (!startNode.isIntegralNumber() || !endNode.isIntegralNumber()
          || !startNode.canConvertToInt() || !endNode.canConvertToInt()
          || start < 0 || end <= start || end > length
          || !finding.path("spanText").isTextual()
          || !claim.analysisText().substring(claim.analysisText().offsetByCodePoints(0, start),
              claim.analysisText().offsetByCodePoints(0, end)).equals(finding.path("spanText").asText())
          || finding.path("riskType").asText().isBlank() || finding.path("explanation").asText().isBlank()
          || !Set.of("low", "medium", "high").contains(finding.path("severity").asText())
          || !Set.of("RISK", "NEEDS_REVIEW").contains(verdict) || !finding.path("citations").isArray())
        throw new Failure(false);
      boolean verified = false;
      for (JsonNode citation : finding.path("citations")) {
        if (citation.path("nodeId").asText().isBlank() || citation.path("quote").asText().isBlank()
            || !citation.path("quoteGrounded").isBoolean()
            || !Set.of("VERIFIED", "UNVERIFIED").contains(citation.path("applicability").asText()))
          throw new Failure(false);
        verified |= citation.path("quoteGrounded").asBoolean()
            && citation.path("applicability").asText().equals("VERIFIED");
      }
      if (verdict.equals("RISK") && !verified) throw new Failure(false);
      needsReview |= verdict.equals("NEEDS_REVIEW"); risk |= verdict.equals("RISK");
    }
    String outcome = report.path("outcome").asText();
    if ((outcome.equals("NO_RISKS_DETECTED") || outcome.equals("OUT_OF_SCOPE"))
        && !report.path("findings").isEmpty()) throw new Failure(false);
    if (outcome.equals("POTENTIAL_RISKS") && !risk || outcome.equals("NEEDS_REVIEW")
        && !claim.mode().equals("stub") && !needsReview) throw new Failure(false);
  }
}
