package com.guardwork.backend.job;

import static com.guardwork.backend.CompanyDossierFixtures.completeDossier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.job.service.JdScanWorker;
import com.guardwork.backend.job.service.JdScanQueue;
import com.guardwork.backend.job.service.JdScanAdapter;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {"jobs.ai.enabled=true", "jobs.ai.worker-enabled=false",
    "company.membership.notifications.worker-enabled=false"})
class JdScanHttpTest {
  @Autowired WebApplicationContext context;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate database;
  @Autowired JdScanWorker worker;
  @Autowired JdScanQueue queue;
  @Autowired JdScanAdapter client;
  @Autowired Clock clock;
  static final AtomicReference<String> RESPONSE = new AtomicReference<>("valid");
  static final HttpServer ADAPTER = adapter();
  MockMvc http;
  static final String JOBS = "/api/companies/me/jobs";

  @TestConfiguration static class TestTime {
    @Bean @Primary Clock scanTestClock() { return new AdjustableClock(); }
  }
  static class AdjustableClock extends Clock {
    Instant now = Instant.parse("2026-11-10T00:00:00Z");
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; }
    public Instant instant() { return now; }
    void advance(long seconds) { now = now.plusSeconds(seconds); }
  }

  @DynamicPropertySource static void adapterProperties(DynamicPropertyRegistry registry) {
    registry.add("jobs.ai.adapter-url", () -> "http://127.0.0.1:" + ADAPTER.getAddress().getPort());
  }

  static HttpServer adapter() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/analyze", exchange -> {
        if (!RESPONSE.get().equals("valid")) {
          byte[] body = "invalid JSON".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(RESPONSE.get().equals("unavailable") ? 503 : 200, body.length);
          exchange.getResponseBody().write(body); exchange.close(); return;
        }
        var json = new ObjectMapper();
        var request = json.readTree(exchange.getRequestBody());
        var report = json.createObjectNode();
        report.put("schemaVersion", "1");
        for (String field : new String[] {"mode", "profile", "legalSnapshot", "asOfDate"})
          report.set(field, request.path(field));
        report.put("outcome", "NEEDS_REVIEW");
        report.putArray("findings"); report.putArray("warnings").add("Stub only");
        report.putObject("usage").put("available", false);
        report.put("internalTrace", "Not part of the public report");
        byte[] body = json.writeValueAsBytes(report);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body); exchange.close();
      });
      server.start(); return server;
    } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
  }

  @BeforeEach void setup() {
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    RESPONSE.set("valid");
    ((AdjustableClock) clock).advance(2000);
    for (int i = 0; i < 30 && worker.runOnce(); i++) { /* Finish earlier tests' queued work. */ }
  }

  @Test void queuesExactSavedRevisionAndReusesItWithoutChangingTheJob() throws Exception {
    String owner = owner();
    JsonNode job = create(owner, "Giữ bản chính CCCD trong thời gian làm việc.");
    String path = scanPath(job);
    JsonNode first = request(owner, path, job);
    assertThat(first.path("status").asText()).isEqualTo("QUEUED");
    assertThat(first.path("mode").asText()).isEqualTo("stub");
    JsonNode second = request(owner, path, job);
    assertThat(second.path("scanId").asLong()).isEqualTo(first.path("scanId").asLong());
    http.perform(get(path + "/" + first.path("scanId").asLong()).header("Authorization", owner))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.outdated").value(false));
    http.perform(get(JOBS + "/" + job.path("id").asLong()).header("Authorization", owner))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(job.path("version").asLong()));
    http.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevisionVersion\":0}"))
        .andExpect(status().isUnauthorized());
  }

  @Test void editedContentMakesTheOldReportOutdatedAndOtherCompaniesCannotReadIt() throws Exception {
    String owner = owner(), stranger = owner();
    JsonNode job = create(owner, "Nội dung ban đầu.");
    String path = scanPath(job);
    JsonNode scan = request(owner, path, job);
    String poll = path + "/" + scan.path("scanId").asLong();
    http.perform(get(poll).header("Authorization", stranger)).andExpect(status().isNotFound());
    http.perform(post(path).header("Authorization", stranger).contentType(MediaType.APPLICATION_JSON)
        .content("{\"expectedRevisionVersion\":0}")).andExpect(status().isNotFound());
    http.perform(get("/api/staff/job-revisions/" + job.path("workingRevision").path("id").asLong()
        + "/scans/" + scan.path("scanId").asLong()).header("Authorization", owner))
        .andExpect(status().isForbidden());
    var edited = mapper.readTree(http.perform(put(JOBS + "/" + job.path("id").asLong() + "/draft")
        .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion", job.path("version").asLong(),
            "expectedRevisionVersion", job.path("workingRevision").path("version").asLong(),
            "content", Map.of("title", "Tuyển bảo vệ", "description", "Nội dung mới.")))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    http.perform(get(poll).header("Authorization", owner)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outdated").value(true));
    http.perform(post(path).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
        .content("{\"expectedRevisionVersion\":0}")).andExpect(status().isConflict());
    assertThat(request(owner, path, edited).path("scanId").asLong()).isNotEqualTo(scan.path("scanId").asLong());
  }

  @Test void workerPersistsTheAdapterReportAndCompletedResultIsReused() throws Exception {
    String owner = owner();
    JsonNode job = create(owner, "JD chạy qua worker " + UUID.randomUUID());
    String path = scanPath(job);
    JsonNode scan = request(owner, path, job);
    for (int i = 0; i < 30 && worker.runOnce(); i++) { /* Drain queued local work. */ }
    http.perform(get(path + "/" + scan.path("scanId").asLong()).header("Authorization", owner))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"))
        .andExpect(jsonPath("$.data.report.outcome").value("NEEDS_REVIEW"))
        .andExpect(jsonPath("$.data.report.mode").value("stub"));
    assertThat(request(owner, path, job).path("scanId").asLong()).isEqualTo(scan.path("scanId").asLong());
  }

  @Test void candidatesReadThePersistedPublishedAnalysisWithoutStartingAnotherScan() throws Exception {
    String owner = owner(), candidate = account();
    JsonNode job = publishable(owner, "Published JD " + UUID.randomUUID());
    var scan = request(owner, scanPath(job), job);
    String publicPath = "/api/jobs/" + job.path("id").asLong();
    http.perform(get(publicPath).header("Authorization", candidate)).andExpect(status().isNotFound());
    publish(owner, job);
    http.perform(get(publicPath)).andExpect(status().isOk()).andExpect(jsonPath("$.data.aiAnalysis").isEmpty());
    assertThat(worker.runOnce()).isTrue();
    long attempts = database.queryForObject("SELECT count(*) FROM jd_scan_attempts", Long.class);
    long runs = database.queryForObject("SELECT count(*) FROM jd_scan_runs", Long.class);
    for (String actor : new String[] {candidate, ""}) {
      http.perform(get(publicPath).header("Authorization", actor)).andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.data.aiAnalysis.report.outcome").value("NEEDS_REVIEW"))
          .andExpect(jsonPath("$.data.aiAnalysis.report.warnings[0]").value("Stub only"))
          .andExpect(jsonPath("$.data.aiAnalysis.mode").value("stub"))
          .andExpect(jsonPath("$.data.aiAnalysis.outdated").value(false))
          .andExpect(jsonPath("$.data.aiAnalysis.completedAt").isNotEmpty())
          .andExpect(jsonPath("$.data.aiAnalysis.asOfDate").isNotEmpty())
          .andExpect(jsonPath("$.data.aiAnalysis.scanId").doesNotExist())
          .andExpect(jsonPath("$.data.aiAnalysis.report.internalTrace").doesNotExist())
          .andExpect(jsonPath("$.data.aiAnalysis.report.usage").doesNotExist());
    }
    assertThat(database.queryForObject("SELECT count(*) FROM jd_scan_attempts", Long.class)).isEqualTo(attempts);
    assertThat(database.queryForObject("SELECT count(*) FROM jd_scan_runs", Long.class)).isEqualTo(runs);
    ((AdjustableClock) clock).advance(86400);
    http.perform(get(publicPath)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.aiAnalysis.outdated").value(true));
    assertThat(requestPublished(owner, job).path("scanId").asLong())
        .isEqualTo(scan.path("scanId").asLong());
    // A date change marks the report outdated without spending on identical content.
    assertThat(worker.runOnce()).isFalse();
    http.perform(get(publicPath)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.aiAnalysis.report.outcome").value("NEEDS_REVIEW"));
    database.update("UPDATE job_postings SET staff_suspended=true WHERE id=?", job.path("id").asLong());
    http.perform(get(publicPath)).andExpect(status().isNotFound());
  }

  @Test void publicAnalysisNeverIncludesRemovedDraftContentOrUnassociatedScans() throws Exception {
    String owner = owner();
    JsonNode job = publishable(owner, "Private text removed before publishing");
    request(owner, scanPath(job), job);
    assertThat(worker.runOnce()).isTrue();
    var fields = mapper.convertValue(job.path("workingRevision").path("content"), Map.class);
    fields.put("description", "Approved text " + UUID.randomUUID());
    JsonNode edited = mapper.readTree(http.perform(put(JOBS + "/" + job.path("id").asLong() + "/draft")
        .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion", job.path("version").asLong(),
            "expectedRevisionVersion", job.path("workingRevision").path("version").asLong(), "content", fields))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    publish(owner, edited);
    http.perform(get("/api/jobs/" + job.path("id").asLong())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.aiAnalysis").isEmpty());
    requestPublished(owner, edited);
    assertThat(worker.runOnce()).isTrue();
    http.perform(get("/api/jobs/" + job.path("id").asLong())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.aiAnalysis.analysisText").value(org.hamcrest.Matchers.containsString("Approved text")));
    // Even identical content in the same Company requires an explicit revision association.
    JsonNode other = publishable(owner, fields.get("description").toString());
    publish(owner, other);
    http.perform(get("/api/jobs/" + other.path("id").asLong())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.aiAnalysis").isEmpty());
  }

  @Test void allAuthenticatedRolesShareOneAnalysisAndReceiveOnlySafeFields() throws Exception {
    String owner = owner(), candidate = account(), staff = staff();
    JsonNode job = publishable(owner, "Shared across roles " + UUID.randomUUID());
    String recruiter = recruiter(job);
    publish(owner, job);
    long version = currentVersion(job);
    String path = sharedPath(job);
    http.perform(get("/api/jobs/" + job.path("id").asLong()).header("Authorization", candidate))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revisionId").value(job.path("workingRevision").path("id").asLong()))
        .andExpect(jsonPath("$.data.revisionVersion").value(version));
    http.perform(get(path + "/latest").header("Authorization", candidate)
        .param("expectedRevisionVersion", Long.toString(version)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    long before = database.queryForObject("SELECT count(*) FROM jd_scan_runs", Long.class);
    try (var threads = Executors.newFixedThreadPool(4)) {
      var requests = new java.util.ArrayList<>(java.util.List.of(candidate, staff, recruiter).stream()
          .map(actor -> threads.submit(() -> sharedRequest(actor, path, version))).toList());
      requests.add(threads.submit(() -> requestPublished(owner, job)));
      for (var pending : requests) assertThat(pending.get(15, TimeUnit.SECONDS).path("status").asText()).isEqualTo("QUEUED");
    }
    assertThat(database.queryForObject("SELECT count(*) FROM jd_scan_runs", Long.class)).isEqualTo(before + 1);
    var claim = queue.claim().orElseThrow();
    for (String actor : new String[] {candidate, staff, recruiter}) {
      http.perform(get(path + "/latest").header("Authorization", actor)
          .param("expectedRevisionVersion", Long.toString(version)))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RUNNING"));
    }
    assertThat(queue.complete(claim, client.analyze(claim))).isTrue();
    long attempts = database.queryForObject("SELECT count(*) FROM jd_scan_attempts", Long.class);
    for (String actor : new String[] {candidate, staff, recruiter}) {
      JsonNode result = sharedRequest(actor, path, version);
      assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
      assertThat(result.path("revisionVersion").asLong()).isEqualTo(version);
      assertThat(result.path("report").path("outcome").asText()).isEqualTo("NEEDS_REVIEW");
      assertThat(result.toString()).doesNotContain("requestedBy", "scanId", "inputContent", "profile",
          "legalSnapshot", "internalTrace", "usage", "failureCode", "lease", "attempt", "availableAt");
      http.perform(get(path + "/latest").header("Authorization", actor)
          .param("expectedRevisionVersion", Long.toString(version)))
          .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.data.status").value("COMPLETED"))
          .andExpect(jsonPath("$.data.report.internalTrace").doesNotExist());
    }
    assertThat(database.queryForObject("SELECT count(*) FROM jd_scan_runs", Long.class)).isEqualTo(before + 1);
    assertThat(database.queryForObject("SELECT count(*) FROM jd_scan_attempts", Long.class)).isEqualTo(attempts);
    ((AdjustableClock) clock).advance(86400);
    assertThat(sharedRequest(candidate, path, version).path("status").asText()).isEqualTo("COMPLETED");
    assertThat(database.queryForObject("SELECT count(*) FROM jd_scan_runs", Long.class)).isEqualTo(before + 1);
    http.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevisionVersion\":" + version + "}"))
        .andExpect(status().isUnauthorized());
    http.perform(get(path + "/latest").param("expectedRevisionVersion", Long.toString(version)))
        .andExpect(status().isUnauthorized());
    http.perform(post(path).header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
        .content("{\"expectedRevisionVersion\":0}")).andExpect(status().isConflict());
    http.perform(get(path + "/latest").header("Authorization", candidate).param("expectedRevisionVersion", "0"))
        .andExpect(status().isConflict());
    http.perform(post(path).header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
        .content("{}")).andExpect(status().isBadRequest());
    http.perform(get(path + "/latest").header("Authorization", candidate)).andExpect(status().isBadRequest());
    database.update("UPDATE job_postings SET staff_suspended=true WHERE id=?", job.path("id").asLong());
    deniedShared(candidate, path, version);
  }

  @Test void recruitersFromDifferentCompaniesCanAnalyzeEachOthersPublishedJobsConcurrently() throws Exception {
    String firstOwner = owner(), secondOwner = owner();
    JsonNode first = publishable(firstOwner, "Cross-company first " + UUID.randomUUID());
    JsonNode second = publishable(secondOwner, "Cross-company second " + UUID.randomUUID());
    String firstRecruiter = recruiter(first), secondRecruiter = recruiter(second);
    publish(firstOwner, first); publish(secondOwner, second);
    var ready = new java.util.concurrent.CountDownLatch(2);
    try (var threads = Executors.newFixedThreadPool(2)) {
      var one = threads.submit(() -> {
        ready.countDown(); assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        return sharedRequest(firstRecruiter, sharedPath(second), currentVersion(second));
      });
      var two = threads.submit(() -> {
        ready.countDown(); assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        return sharedRequest(secondRecruiter, sharedPath(first), currentVersion(first));
      });
      assertThat(one.get(15, TimeUnit.SECONDS).path("status").asText()).isEqualTo("QUEUED");
      assertThat(two.get(15, TimeUnit.SECONDS).path("status").asText()).isEqualTo("QUEUED");
    }
  }

  @Test void retainedCompletedContentWinsOverNewerHistoricalFailures() throws Exception {
    String owner = owner(), candidate = account();
    JsonNode job = publishable(owner, "Historical analysis " + UUID.randomUUID());
    JsonNode first = request(owner, scanPath(job), job);
    assertThat(worker.runOnce()).isTrue();
    // Older releases could create another run solely for date/profile drift.
    database.update("INSERT INTO jd_scan_runs(company_id,input_hash,input_content,analysis_text,profile,"
        + "legal_snapshot,mode,as_of_date,status,available_at,created_at,finished_at,failure_code) "
        + "SELECT company_id,input_hash,input_content,analysis_text,profile || '-old',legal_snapshot,mode,"
        + "as_of_date + 1,'FAILED',now(),now(),now(),'INVALID_RESPONSE' FROM jd_scan_runs WHERE id=?",
        first.path("scanId").asLong());
    publish(owner, job);
    long before = database.queryForObject("SELECT count(*) FROM jd_scan_runs", Long.class);
    long version = currentVersion(job);
    assertThat(sharedRequest(candidate, sharedPath(job), version).path("status").asText()).isEqualTo("COMPLETED");
    http.perform(get(sharedPath(job) + "/latest").header("Authorization", candidate)
        .param("expectedRevisionVersion", Long.toString(version)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));
    assertThat(requestPublished(owner, job).path("scanId").asLong()).isEqualTo(first.path("scanId").asLong());
    assertThat(database.queryForObject("SELECT count(*) FROM jd_scan_runs", Long.class)).isEqualTo(before);
    assertThat(worker.runOnce()).isFalse();
  }

  @Test void privateDraftsChangedContentAndFailedResultsKeepTheirBoundaries() throws Exception {
    String owner = owner(), candidate = account(), staff = staff();
    JsonNode job = publishable(owner, "Secret removed draft " + UUID.randomUUID());
    String recruiter = recruiter(job);
    String path = sharedPath(job);
    deniedShared(candidate, path, 0);
    deniedShared(staff, path, 0);
    deniedShared(recruiter, path, 0); // Membership alone cannot open the owner's private JD.
    sharedRequest(owner, path, 0);
    assertThat(worker.runOnce()).isTrue();
    var fields = mapper.convertValue(job.path("workingRevision").path("content"), Map.class);
    fields.put("description", "Safe published text " + UUID.randomUUID());
    JsonNode edited = mapper.readTree(http.perform(put(JOBS + "/" + job.path("id").asLong() + "/draft")
        .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion", job.path("version").asLong(),
            "expectedRevisionVersion", 0, "content", fields))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    http.perform(get(path + "/latest").header("Authorization", owner).param("expectedRevisionVersion", "1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    publish(owner, edited);
    long version = currentVersion(edited);
    http.perform(get(path + "/latest").header("Authorization", candidate)
        .param("expectedRevisionVersion", Long.toString(version)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    long before = database.queryForObject("SELECT count(*) FROM jd_scan_runs", Long.class);
    assertThat(sharedRequest(candidate, path, version).path("status").asText()).isEqualTo("QUEUED");
    assertThat(database.queryForObject("SELECT count(*) FROM jd_scan_runs", Long.class)).isEqualTo(before + 1);
    RESPONSE.set("malformed");
    assertThat(worker.runOnce()).isTrue();
    for (String actor : new String[] {candidate, staff, recruiter}) {
      JsonNode failed = sharedRequest(actor, path, version);
      assertThat(failed.path("status").asText()).isEqualTo("FAILED");
      assertThat(failed.path("analysisText").isNull()).isTrue();
      assertThat(failed.path("report").isNull()).isTrue();
      assertThat(failed.toString()).doesNotContain("Secret removed draft", "INVALID_RESPONSE", "failureCode");
    }
    assertThat(database.queryForObject("SELECT count(*) FROM jd_scan_runs", Long.class)).isEqualTo(before + 1);
    assertThat(worker.runOnce()).isFalse();
    // A new private working revision must not become accessible through the published job.
    var draft = mapper.readTree(http.perform(post(JOBS + "/" + job.path("id").asLong() + "/draft")
        .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion", database.queryForObject(
            "SELECT version FROM job_postings WHERE id=?", Long.class, job.path("id").asLong())))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    deniedShared(candidate, sharedPath(draft), draft.path("workingRevision").path("version").asLong());
    deniedShared(staff, sharedPath(draft), draft.path("workingRevision").path("version").asLong());
    deniedShared(recruiter, sharedPath(draft), draft.path("workingRevision").path("version").asLong());
  }

  void deniedShared(String actor, String path, long version) throws Exception {
    http.perform(post(path).header("Authorization", actor).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedRevisionVersion", version))))
        .andExpect(status().isNotFound());
    http.perform(get(path + "/latest").header("Authorization", actor)
        .param("expectedRevisionVersion", Long.toString(version))).andExpect(status().isNotFound());
  }

  long currentVersion(JsonNode job) {
    return database.queryForObject("SELECT version FROM job_posting_revisions WHERE id=?", Long.class,
        job.path("workingRevision").path("id").asLong());
  }

  String sharedPath(JsonNode job) { return scanPath(job).replace("/api/companies/me/jobs", "/api/jobs"); }

  JsonNode sharedRequest(String actor, String path, long version) throws Exception {
    return mapper.readTree(http.perform(post(path).header("Authorization", actor)
        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("expectedRevisionVersion", version))))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andReturn().getResponse().getContentAsString()).path("data");
  }

  String recruiter(JsonNode job) throws Exception {
    String token = account();
    long user = context.getBean(com.guardwork.backend.auth.service.JwtService.class).extractUserId(token.substring(7));
    database.update("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?,?,'RECRUITER','ACTIVE',now())",
        job.path("companyId").asLong(), user);
    return token;
  }

  String staff() throws Exception {
    String token = account();
    var jwt = context.getBean(com.guardwork.backend.auth.service.JwtService.class);
    long user = jwt.extractUserId(token.substring(7));
    database.update("UPDATE users SET role='STAFF' WHERE id=?", user);
    var identity = database.queryForMap("SELECT username,email FROM users WHERE id=?", user);
    return "Bearer " + jwt.generateAccessToken(user, (String) identity.get("username"), (String) identity.get("email"), "STAFF");
  }

  JsonNode requestPublished(String owner, JsonNode job) throws Exception {
    long version = database.queryForObject("SELECT version FROM job_posting_revisions WHERE id=?", Long.class,
        job.path("workingRevision").path("id").asLong());
    return mapper.readTree(http.perform(post(scanPath(job)).header("Authorization", owner)
        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("expectedRevisionVersion", version))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
  }

  JsonNode publishable(String owner, String description) throws Exception {
    return mapper.readTree(http.perform(post(JOBS).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("content", Map.of("title", "Tuyển bảo vệ", "description", description,
            "location", "Hồ Chí Minh", "employmentType", "FULL_TIME", "workArrangement", "ONSITE",
            "deadline", "2099-12-01T12:00:00+07:00")))))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
  }

  void publish(String owner, JsonNode job) throws Exception {
    database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?", job.path("companyId").asLong());
    long revision = job.path("workingRevision").path("id").asLong();
    var pending = mapper.readTree(http.perform(post(JOBS + "/" + job.path("id").asLong() + "/revisions/" + revision + "/submit")
        .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
            "expectedJobVersion", job.path("version").asLong(), "expectedRevisionVersion",
            job.path("workingRevision").path("version").asLong(), "confirmed", true))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    String moderator = account();
    var jwt = context.getBean(com.guardwork.backend.auth.service.JwtService.class);
    long user = jwt.extractUserId(moderator.substring(7));
    database.update("UPDATE users SET role='STAFF' WHERE id=?", user);
    var identity = database.queryForMap("SELECT username,email FROM users WHERE id=?", user);
    String staff = "Bearer " + jwt.generateAccessToken(user, (String) identity.get("username"),
        (String) identity.get("email"), "STAFF");
    http.perform(put("/api/staff/job-revisions/" + revision + "/decision").header("Authorization", staff)
        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
            "expectedJobVersion", pending.path("version").asLong(), "expectedRevisionVersion",
            pending.path("workingRevision").path("version").asLong(), "outcome", "APPROVED"))))
        .andExpect(status().isOk());
  }

  @Test void scanPreservesExactCompensationDecimalsInItsInput() throws Exception {
    String owner = owner();
    var job = mapper.readTree(http.perform(post(JOBS).header("Authorization", owner)
        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":{\"description\":\"Exact decimals\","
            + "\"compensation\":{\"minimum\":9999999999999999.99}}}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
    assertThat(request(owner, scanPath(job), job).path("analysisText").asText()).contains("9999999999999999.99");
  }

  @Test void concurrentRequestsAndIdenticalJobsReuseOnlyWithinTheSameCompany() throws Exception {
    String owner = owner();
    JsonNode first = create(owner, "Exact reusable content");
    JsonNode second = create(owner, "Exact reusable content");
    try (var threads = Executors.newFixedThreadPool(2)) {
      var one = threads.submit(() -> request(owner, scanPath(first), first));
      var two = threads.submit(() -> request(owner, scanPath(second), second));
      long scanId = one.get(10, TimeUnit.SECONDS).path("scanId").asLong();
      assertThat(two.get(10, TimeUnit.SECONDS).path("scanId").asLong()).isEqualTo(scanId);
      String stranger = owner(); JsonNode other = create(stranger, "Exact reusable content");
      assertThat(request(stranger, scanPath(other), other).path("scanId").asLong()).isNotEqualTo(scanId);
      http.perform(get(scanPath(other) + "/" + scanId).header("Authorization", stranger))
          .andExpect(status().isNotFound());
    }
  }

  @Test void recruiterLosesScanAccessWhenTheirAssignmentEnds() throws Exception {
    String owner = owner(), recruiter = account();
    JsonNode privateJob = create(owner, "Owner-only draft");
    long company = privateJob.path("companyId").asLong();
    long user = context.getBean(com.guardwork.backend.auth.service.JwtService.class)
        .extractUserId(recruiter.substring(7));
    long member = database.queryForObject("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) "
        + "VALUES (?,?,'RECRUITER','ACTIVE',now()) RETURNING id", Long.class, company, user);
    http.perform(post(scanPath(privateJob)).header("Authorization", recruiter).contentType(MediaType.APPLICATION_JSON)
        .content("{\"expectedRevisionVersion\":0}")).andExpect(status().isNotFound());
    JsonNode assigned = create(recruiter, "Assigned draft");
    var scan = request(recruiter, scanPath(assigned), assigned);
    assertThat(sharedRequest(recruiter, sharedPath(assigned), 0).path("status").asText()).isEqualTo("QUEUED");
    String poll = scanPath(assigned) + "/" + scan.path("scanId").asLong();
    http.perform(delete(JOBS + "/" + assigned.path("id").asLong() + "/assignments/" + member)
        .header("Authorization", owner).param("expectedJobVersion", assigned.path("version").asText()))
        .andExpect(status().isOk());
    http.perform(get(poll).header("Authorization", recruiter)).andExpect(status().isNotFound());
    deniedShared(recruiter, sharedPath(assigned), 0);
    http.perform(get(poll).header("Authorization", owner)).andExpect(status().isOk());
  }

  @Test void malformedProviderOutputFailsAndRefreshingDoesNotSpendAgain() throws Exception {
    String owner = owner(); JsonNode job = create(owner, "Malformed response " + UUID.randomUUID());
    String path = scanPath(job); var scan = request(owner, path, job);
    RESPONSE.set("malformed");
    assertThat(worker.runOnce()).isTrue();
    http.perform(get(path + "/" + scan.path("scanId").asLong()).header("Authorization", owner))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FAILED"))
        .andExpect(jsonPath("$.data.failureCode").value("INVALID_RESPONSE"))
        .andExpect(jsonPath("$.data.report").isEmpty());
    assertThat(request(owner, path, job).path("status").asText()).isEqualTo("FAILED");
    assertThat(worker.runOnce()).isFalse();
  }

  @Test void transientFailuresRetryWithBackoffAndStopAfterThreeAttempts() throws Exception {
    String owner = owner(); JsonNode job = create(owner, "Retry " + UUID.randomUUID());
    String path = scanPath(job); var scan = request(owner, path, job);
    RESPONSE.set("unavailable");
    assertThat(worker.runOnce()).isTrue();
    assertThat(worker.runOnce()).isFalse();
    http.perform(get(path + "/" + scan.path("scanId").asLong()).header("Authorization", owner))
        .andExpect(jsonPath("$.data.status").value("QUEUED"));
    ((AdjustableClock) clock).advance(6); assertThat(worker.runOnce()).isTrue();
    ((AdjustableClock) clock).advance(11); assertThat(worker.runOnce()).isTrue();
    http.perform(get(path + "/" + scan.path("scanId").asLong()).header("Authorization", owner))
        .andExpect(jsonPath("$.data.status").value("FAILED"))
        .andExpect(jsonPath("$.data.failureCode").value("ADAPTER_UNAVAILABLE"));
    ((AdjustableClock) clock).advance(1000); assertThat(worker.runOnce()).isFalse();
  }

  @Test void expiredWorkerCannotOverwriteTheReplacementClaim() throws Exception {
    String owner = owner(); JsonNode job = create(owner, "Lease " + UUID.randomUUID());
    String path = scanPath(job); var scan = request(owner, path, job);
    var first = queue.claim().orElseThrow();
    ((AdjustableClock) clock).advance(721);
    var second = queue.claim().orElseThrow();
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.token()).isNotEqualTo(first.token());
    assertThat(queue.complete(first, client.analyze(first))).isFalse();
    assertThat(queue.fail(first, true)).isFalse();
    http.perform(get(path + "/" + scan.path("scanId").asLong()).header("Authorization", owner))
        .andExpect(jsonPath("$.data.status").value("RUNNING"));
    assertThat(queue.complete(second, client.analyze(second))).isTrue();
    http.perform(get(path + "/" + scan.path("scanId").asLong()).header("Authorization", owner))
        .andExpect(jsonPath("$.data.status").value("COMPLETED"));
  }

  @Test void staffCannotSeeDraftScansOrContentRemovedBeforeSubmission() throws Exception {
    String owner = owner(), staffOwner = owner();
    var jwt = context.getBean(com.guardwork.backend.auth.service.JwtService.class);
    long staffId = jwt.extractUserId(staffOwner.substring(7));
    database.update("UPDATE users SET role='STAFF' WHERE id=?", staffId);
    var identity = database.queryForMap("SELECT username,email FROM users WHERE id=?", staffId);
    String staff = "Bearer " + jwt.generateAccessToken(staffId, (String) identity.get("username"),
        (String) identity.get("email"), "STAFF");
    JsonNode job = create(owner, "Private content removed before submission.");
    String path = scanPath(job); var old = request(owner, path, job);
    long revision = job.path("workingRevision").path("id").asLong();
    String staffPath = "/api/staff/job-revisions/" + revision + "/scans/";
    http.perform(get(staffPath + old.path("scanId").asLong()).header("Authorization", staff))
        .andExpect(status().isNotFound());
    var edited = mapper.readTree(http.perform(put(JOBS + "/" + job.path("id").asLong() + "/draft")
        .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
            "expectedJobVersion", job.path("version").asLong(), "expectedRevisionVersion", 0,
            "content", Map.of("title", "Tuyển bảo vệ", "description", "Submitted content.",
                "location", "Hồ Chí Minh", "employmentType", "FULL_TIME", "workArrangement", "ONSITE",
                "deadline", "2099-12-01T12:00:00+07:00")))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    var current = request(owner, path, edited);
    database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?", job.path("companyId").asLong());
    http.perform(post(JOBS + "/" + job.path("id").asLong() + "/revisions/" + revision + "/submit")
        .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
            "expectedJobVersion", edited.path("version").asLong(), "expectedRevisionVersion",
            edited.path("workingRevision").path("version").asLong(), "confirmed", true))))
        .andExpect(status().isOk());
    long submittedVersion = currentVersion(edited);
    long count = database.queryForObject("SELECT count(*) FROM jd_scan_runs", Long.class);
    assertThat(sharedRequest(staff, sharedPath(edited), submittedVersion).path("status").asText()).isEqualTo("QUEUED");
    assertThat(database.queryForObject("SELECT count(*) FROM jd_scan_runs", Long.class)).isEqualTo(count);
    http.perform(get(sharedPath(edited) + "/latest").header("Authorization", staff)
        .param("expectedRevisionVersion", Long.toString(submittedVersion)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.analysisText").isEmpty());
    http.perform(get(staffPath + current.path("scanId").asLong()).header("Authorization", staff))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.outdated").value(false));
    http.perform(get(staffPath + old.path("scanId").asLong()).header("Authorization", staff))
        .andExpect(status().isNotFound());
  }

  JsonNode request(String actor, String path, JsonNode job) throws Exception {
    return mapper.readTree(http.perform(post(path).header("Authorization", actor)
        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
            "expectedRevisionVersion", job.path("workingRevision").path("version").asLong()))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
  }

  String scanPath(JsonNode job) {
    return JOBS + "/" + job.path("id").asLong() + "/revisions/"
        + job.path("workingRevision").path("id").asLong() + "/scans";
  }

  JsonNode create(String actor, String description) throws Exception {
    return mapper.readTree(http.perform(post(JOBS).header("Authorization", actor)
        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("content",
            Map.of("title", "Tuyển bảo vệ", "description", description)))))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
  }

  String owner() throws Exception {
    String token = account();
    http.perform(completeDossier("POST", "/api/companies/verification-request", mapper.writeValueAsString(Map.of(
        "name", "Công ty thử nghiệm", "taxCode", UUID.randomUUID().toString(),
        "registrationCertificateUrl", "https://example.vn/license.pdf"))).header("Authorization", token))
        .andExpect(status().isCreated());
    return token;
  }

  String account() throws Exception {
    String name = "scan_" + UUID.randomUUID().toString().replace("-", "");
    String email = name + "@example.vn";
    http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("firstName", "Ánh", "lastName", "Nguyễn", "username", name,
            "email", email, "password", "Password123")))).andExpect(status().isCreated());
    database.update("UPDATE users SET email_verified=TRUE WHERE email=?", email);
    String response = http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("usernameOrEmail", email, "password", "Password123"))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    String token = "Bearer " + mapper.readTree(response).path("data").path("accessToken").asText();
    return token;
  }
}
