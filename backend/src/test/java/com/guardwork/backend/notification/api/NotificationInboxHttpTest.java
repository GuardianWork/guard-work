package com.guardwork.backend.notification.api;

import static com.guardwork.backend.CompanyDossierFixtures.completeDossier;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** P0+P1 read endpoints: filters, unread-count, mark-all-read, sinceId polling, and detail. */
@SpringBootTest(properties = "company.membership.notifications.worker-enabled=false")
@ActiveProfiles("test")
class NotificationInboxHttpTest {
  @Autowired WebApplicationContext context;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate database;
  MockMvc http;

  @BeforeEach
  void setup() {
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void filteredListUnreadCountAndMarkAllReadAreRecipientScoped() throws Exception {
    String owner = account();
    long ownerId = userId(owner);
    long company = createdCompany(owner);
    String companyName = "Công ty Giải Pháp Ánh Dương";
    // Oldest -> newest: JOB_ASSIGNED (unread), JOB_UNASSIGNED (read),
    // RECRUITER_ACCEPTED (unread), MEMBERSHIP_REMOVED (read).
    long jobAssigned =
        insert(ownerId, company, companyName, "JOB_ASSIGNED", "RECRUITER", "job", false);
    insert(ownerId, company, companyName, "JOB_UNASSIGNED", "RECRUITER", "job", true);
    insert(ownerId, company, companyName, "RECRUITER_ACCEPTED", "RECRUITER", "company", false);
    long membershipRemoved =
        insert(ownerId, company, companyName, "MEMBERSHIP_REMOVED", "RECRUITER", "company", true);

    http.perform(get("/api/me/notifications").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(4));
    http.perform(get("/api/me/notifications").param("read", "false").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(2));
    http.perform(get("/api/me/notifications").param("read", "true").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(2));
    // Server-side category/event filtering drives the tabs (no client-side guesswork).
    http.perform(get("/api/me/notifications").param("category", "job").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andExpect(jsonPath("$.data.content[0].type").value("JOB_UNASSIGNED"));
    http.perform(get("/api/me/notifications").param("category", "company").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(2));
    http.perform(get("/api/me/notifications").param("eventType", "JOB_ASSIGNED").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].id").value(String.valueOf(jobAssigned)));
    // Case-insensitive wire values.
    http.perform(get("/api/me/notifications").param("eventType", "job_assigned").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(1));
    http.perform(get("/api/me/notifications").param("category", "JOB").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(2));
    // Polling: everything newer than the second-newest item gives just the newest.
    var listBody =
        http.perform(get("/api/me/notifications").header("Authorization", owner))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var content = mapper.readTree(listBody).path("data").path("content");
    String secondNewestId = content.get(1).path("id").asText();
    http.perform(get("/api/me/notifications").param("sinceId", secondNewestId).header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].id").value(String.valueOf(membershipRemoved)));

    // Unread count for the badge.
    http.perform(get("/api/me/notifications/unread-count").header("Authorization", owner))
        .andExpect(jsonPath("$.data.unreadCount").value(2));

    // Detail: own item resolves; foreign/missing items do not leak.
    http.perform(get("/api/me/notifications/" + jobAssigned).header("Authorization", owner))
        .andExpect(jsonPath("$.data.type").value("JOB_ASSIGNED"))
        .andExpect(jsonPath("$.data.read").value(false));
    String stranger = account();
    http.perform(get("/api/me/notifications/" + jobAssigned).header("Authorization", stranger))
        .andExpect(status().isNotFound());
    http.perform(get("/api/me/notifications/9223372036854775807").header("Authorization", owner))
        .andExpect(status().isNotFound());
    http.perform(get("/api/me/notifications").header("Authorization", stranger))
        .andExpect(jsonPath("$.data.totalElements").value(0));
    http.perform(get("/api/me/notifications/unread-count").header("Authorization", stranger))
        .andExpect(jsonPath("$.data.unreadCount").value(0));

    // Invalid filters are rejected, not silently ignored.
    http.perform(get("/api/me/notifications").param("category", "nope").header("Authorization", owner))
        .andExpect(status().isBadRequest());
    http.perform(get("/api/me/notifications").param("eventType", "nope").header("Authorization", owner))
        .andExpect(status().isBadRequest());
    http.perform(get("/api/me/notifications").param("sinceId", "-1").header("Authorization", owner))
        .andExpect(status().isBadRequest());

    // Mark-all in one transaction; repeat is a no-op receipt (updatedCount 0).
    http.perform(patch("/api/me/notifications/read").header("Authorization", owner))
        .andExpect(jsonPath("$.data.updatedCount").value(2))
        .andExpect(jsonPath("$.data.readAt").isNotEmpty());
    http.perform(get("/api/me/notifications/unread-count").header("Authorization", owner))
        .andExpect(jsonPath("$.data.unreadCount").value(0));
    http.perform(get("/api/me/notifications").param("read", "false").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(0));
    http.perform(patch("/api/me/notifications/read").header("Authorization", owner))
        .andExpect(jsonPath("$.data.updatedCount").value(0));
    // Single-read idempotency still holds after mark-all.
    http.perform(patch("/api/me/notifications/" + jobAssigned + "/read").header("Authorization", owner))
        .andExpect(jsonPath("$.data.id").value(jobAssigned));
    http.perform(get("/api/me/notifications")).andExpect(status().isUnauthorized());
  }

  long insert(
      long recipient,
      long company,
      String companyName,
      String eventType,
      String audience,
      String category,
      boolean read)
      throws Exception {
    UUID event = UUID.randomUUID();
    Instant created = Instant.now().minusSeconds(3600);
    Timestamp createdTs = Timestamp.from(created);
    // APPLICATION_* rows require an application_id (ck_application_*_context); the
    // column has no FK, so a random UUID satisfies the check.
    boolean needsApplication = eventType.startsWith("APPLICATION_");
    UUID applicationId = needsApplication ? UUID.randomUUID() : null;
    database.update(
        "INSERT INTO notification_outbox(event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, application_id, created_at, attempt_count, next_attempt_at, delivered_at, error_category, actor_user_id, actor_name) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        event,
        recipient,
        eventType,
        audience,
        company,
        companyName,
        null,
        applicationId,
        createdTs,
        0,
        createdTs,
        createdTs,
        null,
        null,
        null);
    database.update(
        "INSERT INTO notification_inbox(source_event_id, recipient_user_id, event_type, audience, company_id, company_name, job_id, application_id, category, title, body, action_href, actor_user_id, actor_name, created_at, read_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        event,
        recipient,
        eventType,
        audience,
        company,
        companyName,
        null,
        applicationId,
        category,
        eventType + " title",
        eventType + " body",
        null,
        null,
        null,
        createdTs,
        read ? createdTs : null);
    return database.queryForObject(
        "SELECT id FROM notification_inbox WHERE source_event_id=?",
        Long.class,
        event);
  }

  @Test
  void audienceScopingIsolatesPortalViewsForTheSameUser() throws Exception {
    String owner = account();
    long ownerId = userId(owner);
    long company = createdCompany(owner);
    String companyName = "Công ty Giải Pháp Ánh Dương";
    // UNKNOWN is used for the candidate-side row so the fixture avoids the
    // applications FK chain (APPLICATION_* requires a real application row).
    long candidateItem =
        insert(ownerId, company, companyName, "UNKNOWN", "CANDIDATE", "system", false);
    long recruiterItem =
        insert(ownerId, company, companyName, "JOB_ASSIGNED", "RECRUITER", "job", false);

    // Unscoped list still returns both (backwards compatibility).
    http.perform(get("/api/me/notifications").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andExpect(jsonPath("$.data.content[0].audience").exists());
    // Portal-scoped lists isolate each audience.
    http.perform(get("/api/me/notifications").param("audience", "CANDIDATE").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].id").value(String.valueOf(candidateItem)))
        .andExpect(jsonPath("$.data.content[0].audience").value("CANDIDATE"));
    http.perform(get("/api/me/notifications").param("audience", "RECRUITER").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].id").value(String.valueOf(recruiterItem)));
    http.perform(get("/api/me/notifications").param("audience", "candidate").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(1));
    // Scoped badge counts.
    http.perform(get("/api/me/notifications/unread-count").param("audience", "CANDIDATE").header("Authorization", owner))
        .andExpect(jsonPath("$.data.unreadCount").value(1));
    http.perform(get("/api/me/notifications/unread-count").param("audience", "RECRUITER").header("Authorization", owner))
        .andExpect(jsonPath("$.data.unreadCount").value(1));
    // Cross-audience detail and single-read do not leak.
    http.perform(get("/api/me/notifications/" + candidateItem).param("audience", "RECRUITER").header("Authorization", owner))
        .andExpect(status().isNotFound());
    http.perform(
            patch("/api/me/notifications/" + candidateItem + "/read")
                .param("audience", "RECRUITER")
                .header("Authorization", owner))
        .andExpect(status().isNotFound());
    http.perform(get("/api/me/notifications/" + recruiterItem).param("audience", "RECRUITER").header("Authorization", owner))
        .andExpect(jsonPath("$.data.audience").value("RECRUITER"));
    // Unknown audience is rejected.
    http.perform(get("/api/me/notifications").param("audience", "nope").header("Authorization", owner))
        .andExpect(status().isBadRequest());
    // Mark-all is audience-scoped: candidate portal does not touch recruiter unread.
    http.perform(patch("/api/me/notifications/read").param("audience", "CANDIDATE").header("Authorization", owner))
        .andExpect(jsonPath("$.data.updatedCount").value(1));
    http.perform(get("/api/me/notifications/unread-count").param("audience", "RECRUITER").header("Authorization", owner))
        .andExpect(jsonPath("$.data.unreadCount").value(1));
    http.perform(get("/api/me/notifications/unread-count").param("audience", "CANDIDATE").header("Authorization", owner))
        .andExpect(jsonPath("$.data.unreadCount").value(0));
    // Unscoped mark-all still clears the remaining audience (backwards compatibility).
    http.perform(patch("/api/me/notifications/read").header("Authorization", owner))
        .andExpect(jsonPath("$.data.updatedCount").value(1));
  }

  long userId(String token) throws Exception {
    String body =
        http.perform(get("/api/auth/me").header("Authorization", token))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(body).path("data").path("id").asLong();
  }

  long createdCompany(String token) throws Exception {
    String body =
        http.perform(
                completeDossier("POST", "/api/companies/verification-request", company(UUID.randomUUID().toString()))
                    .header("Authorization", token))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(body).path("data").path("id").asLong();
  }

  String account() throws Exception {
    String name = "user_" + UUID.randomUUID().toString().replace("-", "");
    return account(name + "@example.vn");
  }

  String account(String email) throws Exception {
    String name = "user_" + UUID.randomUUID().toString().replace("-", "");
    http.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "firstName",
                            "Ánh",
                            "lastName",
                            "Nguyễn",
                            "username",
                            name,
                            "email",
                            email,
                            "password",
                            "Password123"))))
        .andExpect(status().isCreated());
    database.update("UPDATE users SET email_verified=TRUE WHERE email=?", email);
    String response =
        http.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            Map.of("usernameOrEmail", email, "password", "Password123"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return "Bearer " + mapper.readTree(response).path("data").path("accessToken").asText();
  }

  String company(String taxCode) throws Exception {
    return mapper.writeValueAsString(
        Map.of(
            "name",
            "Công ty Giải Pháp Ánh Dương",
            "taxCode",
            taxCode,
            "email",
            taxCode + "@company.vn",
            "registrationCertificateUrl",
            "https://storage.guardwork.vn/giấy-phép.pdf"));
  }
}
