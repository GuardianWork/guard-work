package com.guardwork.backend.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

@SpringBootTest
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(CompanyInvitationHttpTest.ControlledDelivery.class)
class CompanyInvitationHttpTest {
  @Autowired WebApplicationContext context;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate database;
  @Autowired CapturingMailer mailer;
  @Autowired MutableClock clock;
  MockMvc http;

  @BeforeEach
  void setUp() {
    mailer.reset();
    clock.now = java.time.Instant.parse("2026-10-08T16:59:59Z");
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void verifiedOwnerIssuesPrivateRecruiterInvitationAndListsOnlyMetadata() throws Exception {
    String owner = account();
    long companyId = createdCompany(owner);
    database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?", companyId);
    http.perform(
            post("/api/companies/me/invitations")
                .header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\" New.Person@Example.VN \"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.email").value("new.person@example.vn"))
        .andExpect(jsonPath("$.data.companyRole").value("RECRUITER"))
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andExpect(jsonPath("$.data.secret").doesNotExist())
        .andExpect(jsonPath("$.data.secretHash").doesNotExist());
    http.perform(get("/api/companies/me/invitations").header("Authorization", owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1));
  }

  @Test
  void privateDeliveryPersistsOnlyHashAndResendRotatesCurrentInvitation() throws Exception {
    String owner = account();
    long company = verifiedCompany(owner);
    var first = issue(owner, "fresh.person@example.vn", 201);
    String secret = mailer.secrets.getLast();
    assertThat(secret).hasSize(43);
    assertThat(mailer.recipients).containsExactly("fresh.person@example.vn");
    assertThat(first.path("deliveryStatus").asText()).isEqualTo("PROVIDER_ACCEPTED");
    assertThat(first.path("issuedAt").asText()).isEqualTo("2026-10-08T16:59:59Z");
    assertThat(first.path("expiresAt").asText()).isEqualTo("2026-10-15T16:59:59Z");
    // Hash-only storage is part of the approved persistence boundary, not a business-side query.
    String persisted =
        database.queryForObject(
            "SELECT secret_hash FROM company_invitations WHERE id=?",
            String.class,
            first.path("id").asLong());
    assertThat(persisted).hasSize(64).isNotEqualTo(secret);
    long id = first.path("id").asLong();
    clock.now = java.time.Instant.parse("2026-10-09T00:00:00Z");
    http.perform(
            post("/api/companies/me/invitations/" + id + "/resend").header("Authorization", owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(id))
        .andExpect(jsonPath("$.data.expiresAt").value("2026-10-16T00:00:00Z"));
    assertThat(mailer.secrets.getLast()).isNotEqualTo(secret);
    assertThat(
            database.queryForObject(
                "SELECT secret_hash FROM company_invitations WHERE id=?", String.class, id))
        .isNotEqualTo(persisted);
    http.perform(get("/api/companies/me/invitations").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(1));
    http.perform(get("/api/auth/me").header("Authorization", owner))
        .andExpect(jsonPath("$.data.activeMembership.companyRole").value("OWNER"));
  }

  @Test
  void expiryAtExactBoundaryCommitsExpiredStateBeforeAllowingReplacement() throws Exception {
    String owner = account();
    verifiedCompany(owner);
    long id = issue(owner, "expiry@example.vn", 201).path("id").asLong();
    clock.now = java.time.Instant.parse("2026-10-15T16:59:59Z");
    http.perform(
            post("/api/companies/me/invitations/" + id + "/resend").header("Authorization", owner))
        .andExpect(status().isNotFound());
    http.perform(get("/api/companies/me/invitations?status=EXPIRED").header("Authorization", owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1));
    issue(owner, "expiry@example.vn", 201);
    http.perform(get("/api/companies/me/invitations?status=PENDING").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(1));
  }

  @Test
  void failedAndUncertainDeliveryRemainPendingAndManualResendRecovers() throws Exception {
    String owner = account();
    verifiedCompany(owner);
    mailer.outcome = com.guardwork.backend.invitation.model.DeliveryStatus.FAILED;
    var invitation = issue(owner, "retry@example.vn", 201);
    assertThat(invitation.path("deliveryStatus").asText()).isEqualTo("FAILED");
    String original = mailer.secrets.getLast();
    long id = invitation.path("id").asLong();
    mailer.outcome = com.guardwork.backend.invitation.model.DeliveryStatus.UNKNOWN;
    http.perform(
            post("/api/companies/me/invitations/" + id + "/resend").header("Authorization", owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.deliveryStatus").value("UNKNOWN"))
        .andExpect(jsonPath("$.data.status").value("PENDING"));
    mailer.outcome = com.guardwork.backend.invitation.model.DeliveryStatus.PROVIDER_ACCEPTED;
    http.perform(
            post("/api/companies/me/invitations/" + id + "/resend").header("Authorization", owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.deliveryStatus").value("PROVIDER_ACCEPTED"));
    assertThat(mailer.secrets).hasSize(3).doesNotHaveDuplicates();
    assertThat(mailer.secrets.getLast()).isNotEqualTo(original);
  }

  @Test
  void freshOwnerAuthorityVerificationAndRecipientPrivacyAreEnforced() throws Exception {
    String owner = account();
    long company = createdCompany(owner);
    issue(owner, "private@example.vn", 409);
    database.update("UPDATE companies SET verification_status='REJECTED' WHERE id=?", company);
    issue(owner, "private@example.vn", 409);
    database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?", company);
    String otherOwner = account();
    createdCompany(otherOwner);
    long unrelatedId = userId(otherOwner);
    String relatedEmail =
        database.queryForObject("SELECT email FROM users WHERE id=?", String.class, unrelatedId);
    var invitation = issue(owner, relatedEmail, 201);
    assertThat(invitation.toString()).doesNotContain("userId", "membership", "exists");
    long id = invitation.path("id").asLong();
    http.perform(
            post("/api/companies/me/invitations/" + id + "/resend")
                .header("Authorization", otherOwner))
        .andExpect(status().isConflict());
    http.perform(delete("/api/companies/me/invitations/" + id).header("Authorization", otherOwner))
        .andExpect(status().isNotFound());
    String recruiter = account();
    database.update(
        "INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES"
            + " (?,?,'RECRUITER','ACTIVE',now())",
        company,
        userId(recruiter));
    issue(recruiter, "denied@example.vn", 403);
    http.perform(get("/api/companies/me/invitations").header("Authorization", recruiter))
        .andExpect(status().isForbidden());
    database.update("UPDATE companies SET is_banned=true WHERE id=?", company);
    issue(owner, "denied@example.vn", 404);
    database.update("UPDATE companies SET is_banned=false WHERE id=?", company);
    database.update("UPDATE users SET status='SUSPENDED' WHERE id=?", userId(owner));
    issue(owner, "denied@example.vn", 403);
    http.perform(
            post("/api/companies/me/invitations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"anonymous@example.vn\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void revokeKeepsHistoryAndConcurrentNormalizedIssueHasOneWinner() throws Exception {
    String owner = account();
    verifiedCompany(owner);
    CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first = workers.submit(() -> racingIssue(owner, " Concurrent@Example.VN ", ready, start));
      var second = workers.submit(() -> racingIssue(owner, "concurrent@example.vn", ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(
              java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 409);
    }
    assertThat(mailer.secrets).hasSize(1);
    var list =
        mapper.readTree(
            http.perform(get("/api/companies/me/invitations").header("Authorization", owner))
                .andReturn()
                .getResponse()
                .getContentAsString());
    long id = list.path("data").path("content").get(0).path("id").asLong();
    http.perform(delete("/api/companies/me/invitations/" + id).header("Authorization", owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("REVOKED"));
    http.perform(
            post("/api/companies/me/invitations/" + id + "/resend").header("Authorization", owner))
        .andExpect(status().isNotFound());
    issue(owner, "concurrent@example.vn", 201);
    http.perform(get("/api/companies/me/invitations?status=REVOKED").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(1));
  }

  @Test
  void auditFailureRollsBackInvitationAndSendsNoMail() throws Exception {
    String owner = account();
    verifiedCompany(owner);
    long actor = userId(owner);
    database.execute(
        "CREATE FUNCTION gw005_reject_audit() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION"
            + " 'controlled failure' USING ERRCODE='23514'; END; $$ LANGUAGE plpgsql");
    database.execute(
        "CREATE TRIGGER gw005_reject_audit BEFORE INSERT ON company_membership_audit_events FOR"
            + " EACH ROW WHEN (NEW.actor_user_id="
            + actor
            + ") EXECUTE FUNCTION gw005_reject_audit()");
    try {
      issue(owner, "atomic@example.vn", 500);
      assertThat(mailer.secrets).isEmpty();
      http.perform(get("/api/companies/me/invitations").header("Authorization", owner))
          .andExpect(jsonPath("$.data.totalElements").value(0));
    } finally {
      database.execute("DROP TRIGGER gw005_reject_audit ON company_membership_audit_events");
      database.execute("DROP FUNCTION gw005_reject_audit()");
    }
    issue(owner, "atomic@example.vn", 201);
  }

  @Test
  void invalidEmailAndPaginationAreSafeValidationErrors() throws Exception {
    String owner = account();
    verifiedCompany(owner);
    for (String email :
        new String[] {"bad", "x@y", "x\ny@example.vn", "x".repeat(250) + "@example.vn"})
      issue(owner, email, 400);
    for (String query : new String[] {"?status=unknown", "?page=-1", "?size=0", "?size=101"})
      http.perform(get("/api/companies/me/invitations" + query).header("Authorization", owner))
          .andExpect(status().isBadRequest());
  }

  @Test
  void slowerPreviousDeliveryCannotOverwriteResendOrRevocationMetadata() throws Exception {
    String owner = account();
    verifiedCompany(owner);
    mailer.blockNext = new CountDownLatch(1);
    mailer.release = new CountDownLatch(1);
    CountDownLatch deliveryStarted = mailer.blockNext;
    try (var worker = Executors.newSingleThreadExecutor()) {
      var issuing = worker.submit(() -> issue(owner, "race@example.vn", 201));
      assertThat(deliveryStarted.await(5, TimeUnit.SECONDS)).isTrue();
      // The invitation is already visible through another HTTP transaction while provider send is
      // blocked.
      var list =
          mapper.readTree(
              http.perform(get("/api/companies/me/invitations").header("Authorization", owner))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.data.totalElements").value(1))
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      long id = list.path("data").path("content").get(0).path("id").asLong();
      http.perform(
              post("/api/companies/me/invitations/" + id + "/resend")
                  .header("Authorization", owner))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.deliveryStatus").value("PROVIDER_ACCEPTED"));
      mailer.release.countDown();
      assertThat(issuing.get(10, TimeUnit.SECONDS).path("deliveryStatus").asText())
          .isEqualTo("PROVIDER_ACCEPTED");
      assertThat(mailer.secrets).hasSize(2).doesNotHaveDuplicates();
      mailer.blockNext = new CountDownLatch(1);
      mailer.release = new CountDownLatch(1);
      CountDownLatch resendStarted = mailer.blockNext;
      var resending =
          worker.submit(
              () ->
                  http.perform(
                          post("/api/companies/me/invitations/" + id + "/resend")
                              .header("Authorization", owner))
                      .andReturn());
      assertThat(resendStarted.await(5, TimeUnit.SECONDS)).isTrue();
      http.perform(delete("/api/companies/me/invitations/" + id).header("Authorization", owner))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.status").value("REVOKED"));
      mailer.release.countDown();
      resending.get(10, TimeUnit.SECONDS);
      http.perform(
              get("/api/companies/me/invitations?status=REVOKED").header("Authorization", owner))
          .andExpect(jsonPath("$.data.content[0].deliveryStatus").value("UNKNOWN"));
    } finally {
      mailer.release.countDown();
    }
  }

  @Test
  void malformedPaginationKeepsApprovedErrorEnvelope() throws Exception {
    String owner = account();
    verifiedCompany(owner);
    http.perform(get("/api/companies/me/invitations?page=oops").header("Authorization", owner))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message").value("40000 BAD_REQUEST: Invalid request parameter"))
        .andExpect(jsonPath("$.data").isEmpty());
  }

  int racingIssue(String owner, String email, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("race did not start");
    return http.perform(
            post("/api/companies/me/invitations")
                .header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email))))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  com.fasterxml.jackson.databind.JsonNode issue(String owner, String email, int expectedStatus)
      throws Exception {
    String response =
        http.perform(
                post("/api/companies/me/invitations")
                    .header("Authorization", owner)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("email", email))))
            .andExpect(status().is(expectedStatus))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).doesNotContain("secret", "hash");
    return mapper.readTree(response).path("data");
  }

  long verifiedCompany(String owner) throws Exception {
    long id = createdCompany(owner);
    database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?", id);
    return id;
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class ControlledDelivery {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    CapturingMailer capturingMailer() {
      return new CapturingMailer();
    }

    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    MutableClock mutableClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends java.time.Clock {
    volatile java.time.Instant now;

    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    public java.time.Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    public java.time.Instant instant() {
      return now;
    }
  }

  static class CapturingMailer
      implements com.guardwork.backend.invitation.mail.CompanyInvitationMailer {
    final java.util.List<String> secrets = new java.util.concurrent.CopyOnWriteArrayList<>();
    final java.util.List<String> recipients = new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile com.guardwork.backend.invitation.model.DeliveryStatus outcome;
    volatile CountDownLatch blockNext;
    volatile CountDownLatch release;
    final java.util.concurrent.atomic.AtomicBoolean blocking =
        new java.util.concurrent.atomic.AtomicBoolean();

    void reset() {
      secrets.clear();
      recipients.clear();
      blockNext = null;
      release = null;
      blocking.set(false);
      outcome = com.guardwork.backend.invitation.model.DeliveryStatus.PROVIDER_ACCEPTED;
    }

    public com.guardwork.backend.invitation.model.DeliveryStatus send(
        String recipient, String company, java.time.Instant expiry, String secret) {
      recipients.add(recipient);
      secrets.add(secret);
      if (blockNext != null && blocking.compareAndSet(false, true)) {
        CountDownLatch blocked = blockNext;
        blockNext = null;
        blocked.countDown();
        try {
          if (!release.await(5, TimeUnit.SECONDS))
            throw new AssertionError("delivery race did not release");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        } finally {
          blocking.set(false);
        }
        return com.guardwork.backend.invitation.model.DeliveryStatus.FAILED;
      }
      return outcome;
    }
  }

  long userId(String token) throws Exception {
    String body =
        http.perform(get("/api/auth/me").header("Authorization", token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(body).path("data").path("id").asLong();
  }

  long createdCompany(String token) throws Exception {
    String body =
        http.perform(
                post("/api/companies/verification-request")
                    .header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(company(UUID.randomUUID().toString())))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(body).path("data").path("id").asLong();
  }

  String account() throws Exception {
    String name = "user_" + UUID.randomUUID().toString().replace("-", "");
    String email = name + "@example.vn";
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
