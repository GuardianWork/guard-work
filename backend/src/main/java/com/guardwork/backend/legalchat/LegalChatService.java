package com.guardwork.backend.legalchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.job.service.JdScanSettings;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Stateless: only the question and analysis identity leave the authenticated backend. */
@Service
public class LegalChatService {
  private final JdScanSettings settings;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final URI endpoint;

  public LegalChatService(JdScanSettings settings, ObjectMapper mapper, Clock clock) {
    this.settings = settings; this.mapper = mapper; this.clock = clock;
    URI origin = URI.create(settings.adapterUrl());
    if (!"http".equals(origin.getScheme()) || !Set.of("127.0.0.1", "localhost", "[::1]").contains(origin.getHost())
        || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
        || !(origin.getPath().isEmpty() || origin.getPath().equals("/")))
      throw new IllegalArgumentException("Legal chat adapter must use a loopback HTTP origin");
    endpoint = URI.create(settings.adapterUrl().replaceAll("/$", "") + "/chat");
  }

  public Map<String, Object> chat(Map<String, Object> request) {
    if (request == null || !(request.get("question") instanceof String))
      throw invalid();
    String question = ((String) request.get("question")).strip();
    int length = question.codePointCount(0, question.length());
    if (length < 1 || length > 2000) throw invalid();
    if (!settings.enabled() || !settings.mode().equals("crawler"))
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "50320 LEGAL_CHAT_REAL_AI_REQUIRED");
    String date = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Ho_Chi_Minh")).toString();
    try {
      String body = mapper.writeValueAsString(Map.of("question", question, "mode", settings.mode(),
          "profile", settings.profile(), "legalSnapshot", settings.legalSnapshot(), "asOfDate", date));
      var operation = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(660))
          .header("Content-Type", "application/json").header("X-GuardWork-AI-Key", settings.sharedKey())
          .POST(HttpRequest.BodyPublishers.ofString(body)).build();
      var response = client.send(operation, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() == 429)
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "42920 LEGAL_CHAT_BUSY");
      if (response.statusCode() != 200 || response.body().length > 524288) throw unavailable();
      JsonNode report = mapper.readTree(response.body());
      validate(report, date);
      return mapper.convertValue(report, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt(); throw unavailable();
    } catch (IOException failure) {
      // Raw provider messages, user input and model output are deliberately not logged.
      throw unavailable();
    }
  }

  private void validate(JsonNode report, String date) {
    if (report == null || !report.isObject() || !report.path("schemaVersion").asText().equals("1")
        || !report.path("mode").asText().equals("crawler")
        || !report.path("profile").asText().equals(settings.profile())
        || !report.path("legalSnapshot").asText().equals(settings.legalSnapshot())
        || !report.path("asOfDate").asText().equals(date)
        || !Set.of("ANSWERED", "INSUFFICIENT_CONTEXT").contains(report.path("status").asText())
        || !report.path("answer").isTextual() || report.path("answer").asText().isBlank()
        || report.path("answer").asText().codePointCount(0, report.path("answer").asText().length()) > 6000
        || !report.path("model").isTextual() || report.path("model").asText().isBlank()
        || !report.path("citations").isArray() || report.path("citations").size() > 20
        || !report.path("warnings").isArray() || !report.path("usage").path("available").isBoolean())
      throw unavailable();
    for (JsonNode warning : report.path("warnings")) if (!warning.isTextual()) throw unavailable();
    if (report.path("status").asText().equals("ANSWERED") && report.path("citations").isEmpty()) throw unavailable();
    if (report.path("status").asText().equals("INSUFFICIENT_CONTEXT") && !report.path("citations").isEmpty()) throw unavailable();
    for (JsonNode citation : report.path("citations")) {
      for (String field : new String[] {"nodeId", "label", "documentNumber", "quote"})
        if (!citation.path(field).isTextual() || citation.path(field).asText().isBlank()) throw unavailable();
      if (!citation.path("quoteGrounded").isBoolean() || !citation.path("quoteGrounded").asBoolean()
          || !Set.of("VERIFIED", "UNVERIFIED").contains(citation.path("applicability").asText())
          || !citation.path("effectiveStatus").isTextual()) throw unavailable();
    }
  }

  private static ResponseStatusException invalid() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "40024 INVALID_LEGAL_QUESTION");
  }
  private static ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "50321 LEGAL_CHAT_UNAVAILABLE");
  }
}
