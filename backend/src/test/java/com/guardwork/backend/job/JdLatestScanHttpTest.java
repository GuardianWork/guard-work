package com.guardwork.backend.job;

import static com.guardwork.backend.CompanyDossierFixtures.completeDossier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.auth.service.JwtService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {"jobs.ai.enabled=true", "jobs.ai.worker-enabled=false",
    "company.membership.notifications.worker-enabled=false"})
class JdLatestScanHttpTest {
  @Autowired WebApplicationContext context;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate database;
  @Autowired JwtService jwt;
  MockMvc http;
  static final String JOBS = "/api/companies/me/jobs";

  @BeforeEach void setup() {
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test void latestIsAReadOnlyLookupAndNotANumericScanId() throws Exception {
    String owner = owner();
    var job = create(owner, "Saved content " + UUID.randomUUID());
    String latest = scanPath(job) + "/latest";
    http.perform(get(latest).header("Authorization", owner).param("expectedRevisionVersion", "0"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    var scan = request(owner, job);
    long runCount = database.queryForObject("SELECT count(*) FROM jd_scan_runs", Long.class);
    long linkCount = database.queryForObject("SELECT count(*) FROM jd_revision_scans", Long.class);
    http.perform(get(latest).header("Authorization", owner).param("expectedRevisionVersion", "0"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.data.scanId").value(scan.path("scanId").asLong()))
        .andExpect(jsonPath("$.data.status").value("QUEUED"));
    http.perform(get(scanPath(job) + "/" + scan.path("scanId").asLong()).header("Authorization", owner))
        .andExpect(status().isOk());
    assertThat(database.queryForObject("SELECT count(*) FROM jd_scan_runs", Long.class)).isEqualTo(runCount);
    assertThat(database.queryForObject("SELECT count(*) FROM jd_revision_scans", Long.class)).isEqualTo(linkCount);
  }

  @Test void latestEnforcesAccessAndExpectedVersion() throws Exception {
    String owner = owner(), stranger = owner();
    var job = create(owner, "Access checks");
    String latest = scanPath(job) + "/latest";
    request(owner, job);
    http.perform(get(latest).param("expectedRevisionVersion", "0")).andExpect(status().isUnauthorized());
    http.perform(get(latest).header("Authorization", stranger).param("expectedRevisionVersion", "0"))
        .andExpect(status().isNotFound());
    http.perform(get(latest).header("Authorization", owner)).andExpect(status().isBadRequest());
    http.perform(get(latest).header("Authorization", owner).param("expectedRevisionVersion", "-1"))
        .andExpect(status().isBadRequest());
    http.perform(get(latest).header("Authorization", owner).param("expectedRevisionVersion", "1"))
        .andExpect(status().isConflict());
  }

  @Test void editedContentDoesNotRestoreOldScanAndStaffCannotDiscoverDrafts() throws Exception {
    String owner = owner(), staff = staff();
    var job = create(owner, "Removed private content");
    request(owner, job);
    long revisionId = job.path("workingRevision").path("id").asLong();
    String staffLatest = "/api/staff/job-revisions/" + revisionId + "/scans/latest";
    http.perform(get(staffLatest).header("Authorization", staff).param("expectedRevisionVersion", "0"))
        .andExpect(status().isNotFound());
    var edited = mapper.readTree(http.perform(put(JOBS + "/" + job.path("id").asLong() + "/draft")
        .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion", job.path("version").asLong(),
            "expectedRevisionVersion", 0, "content", Map.of("title", "Published role",
                "description", "Submitted current content.", "location", "Hồ Chí Minh",
                "employmentType", "FULL_TIME", "workArrangement", "ONSITE",
                "deadline", "2099-12-01T12:00:00+07:00")))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    String version = edited.path("workingRevision").path("version").asText();
    http.perform(get(scanPath(job) + "/latest").header("Authorization", owner).param("expectedRevisionVersion", version))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?", job.path("companyId").asLong());
    var pending = mapper.readTree(http.perform(post(JOBS + "/" + job.path("id").asLong() + "/revisions/" + revisionId + "/submit")
        .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion", edited.path("version").asLong(),
            "expectedRevisionVersion", edited.path("workingRevision").path("version").asLong(), "confirmed", true))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    String submittedVersion = pending.path("workingRevision").path("version").asText();
    http.perform(get(staffLatest).header("Authorization", owner).param("expectedRevisionVersion", submittedVersion))
        .andExpect(status().isForbidden());
    http.perform(get(staffLatest).header("Authorization", staff).param("expectedRevisionVersion", submittedVersion))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    http.perform(get(staffLatest).header("Authorization", staff).param("expectedRevisionVersion", version))
        .andExpect(status().isConflict());
    var scan = request(owner, pending);
    http.perform(get(staffLatest).header("Authorization", staff).param("expectedRevisionVersion", submittedVersion))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.data.scanId").value(scan.path("scanId").asLong()));
  }

  @Test void stateOnlyVersionChangesRetainScansAndLookupUsesTheNewestAssociation() throws Exception {
    String owner = owner(), staff = staff();
    var job = create(owner, "Same saved content " + UUID.randomUUID());
    var original = request(owner, job);
    long revisionId = job.path("workingRevision").path("id").asLong();
    database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?", job.path("companyId").asLong());
    var pending = mapper.readTree(http.perform(post(JOBS + "/" + job.path("id").asLong() + "/revisions/" + revisionId + "/submit")
        .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion", job.path("version").asLong(),
            "expectedRevisionVersion", 0, "confirmed", true))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    String latest = "/api/staff/job-revisions/" + revisionId + "/scans/latest";
    http.perform(get(latest).header("Authorization", staff).param("expectedRevisionVersion", "1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.scanId").value(original.path("scanId").asLong()))
        .andExpect(jsonPath("$.data.revisionVersion").value(0));
    assertThat(request(owner, pending).path("scanId").asLong()).isEqualTo(original.path("scanId").asLong());
    http.perform(get(latest).header("Authorization", staff).param("expectedRevisionVersion", "1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.revisionVersion").value(1));
  }

  JsonNode request(String actor, JsonNode job) throws Exception {
    return mapper.readTree(http.perform(post(scanPath(job)).header("Authorization", actor)
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
            Map.of("title", "Saved role", "description", description, "location", "Hồ Chí Minh",
                "employmentType", "FULL_TIME", "workArrangement", "ONSITE",
                "deadline", "2099-12-01T12:00:00+07:00")))))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
  }

  String owner() throws Exception {
    String actor = account();
    http.perform(completeDossier("POST", "/api/companies/verification-request", mapper.writeValueAsString(Map.of(
        "name", "Lookup test company", "taxCode", UUID.randomUUID().toString(),
        "registrationCertificateUrl", "https://example.vn/license.pdf"))).header("Authorization", actor))
        .andExpect(status().isCreated());
    return actor;
  }

  String staff() throws Exception {
    String actor = account();
    long id = jwt.extractUserId(actor.substring(7));
    database.update("UPDATE users SET role='STAFF' WHERE id=?", id);
    var identity = database.queryForMap("SELECT username,email FROM users WHERE id=?", id);
    return "Bearer " + jwt.generateAccessToken(id, (String) identity.get("username"),
        (String) identity.get("email"), "STAFF");
  }

  String account() throws Exception {
    String name = "latest_" + UUID.randomUUID().toString().replace("-", "");
    String email = name + "@example.vn";
    http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("firstName", "Ánh", "lastName", "Nguyễn", "username", name,
            "email", email, "password", "Password123")))).andExpect(status().isCreated());
    database.update("UPDATE users SET email_verified=TRUE WHERE email=?", email);
    String response = http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("usernameOrEmail", email, "password", "Password123"))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return "Bearer " + mapper.readTree(response).path("data").path("accessToken").asText();
  }
}
