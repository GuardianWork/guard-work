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
    String poll = scanPath(assigned) + "/" + scan.path("scanId").asLong();
    http.perform(delete(JOBS + "/" + assigned.path("id").asLong() + "/assignments/" + member)
        .header("Authorization", owner).param("expectedJobVersion", assigned.path("version").asText()))
        .andExpect(status().isOk());
    http.perform(get(poll).header("Authorization", recruiter)).andExpect(status().isNotFound());
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
