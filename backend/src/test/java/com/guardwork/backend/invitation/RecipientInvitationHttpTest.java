package com.guardwork.backend.invitation;

import static com.guardwork.backend.CompanyDossierFixtures.completeDossier;

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

@SpringBootTest(properties = "company.membership.notifications.worker-enabled=false")
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(RecipientInvitationHttpTest.ControlledDelivery.class)
class RecipientInvitationHttpTest {
  @Autowired WebApplicationContext context;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate database;
  @Autowired CapturingMailer mailer;
  @Autowired MutableClock clock;
  @Autowired com.guardwork.backend.notification.service.NotificationDispatcher dispatcher;
  MockMvc http;

  @BeforeEach
  void setup() {
    mailer.reset();
    clock.now = java.time.Instant.parse("2026-10-08T16:59:59Z");
    http =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(
                context.getBean(
                    com.guardwork.backend.invitation.controller.InvitationPrivacyFilter.class))
            .apply(springSecurity())
            .build();
  }

  @Test
  void recipientPreviewsExplicitlyAcceptsAndOwnerReadsOnePrivateNotification() throws Exception {
    String owner = account(), recipient = account();
    long company = verifiedCompany(owner);
    String secret = invite(owner, email(recipient));
    http.perform(get("/api/auth/me").header("Authorization", recipient))
        .andExpect(jsonPath("$.data.activeMembership").isEmpty());
    secretPost("preview", recipient, secret, null)
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.data.companyId").value(company))
        .andExpect(jsonPath("$.data.companyRole").value("RECRUITER"));
    http.perform(get("/api/auth/me").header("Authorization", recipient))
        .andExpect(jsonPath("$.data.activeMembership").isEmpty());
    secretPost("accept", recipient, secret, false).andExpect(status().isBadRequest());
    var accepted =
        mapper
            .readTree(
                secretPost("accept", recipient, secret, true)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.membershipStatus").value("ACTIVE"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("data");
    secretPost("accept", recipient, secret, true)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.membershipId").value(accepted.path("membershipId").asLong()));
    http.perform(get("/api/auth/me").header("Authorization", recipient))
        .andExpect(jsonPath("$.data.activeMembership.companyRole").value("RECRUITER"));
    http.perform(get("/api/companies/me/verification").header("Authorization", recipient))
        .andExpect(status().isForbidden());
    http.perform(get("/api/companies/me/invitations").header("Authorization", recipient))
        .andExpect(status().isForbidden());
    var inbox =
        mapper
            .readTree(
                http.perform(get("/api/me/notifications").header("Authorization", owner))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].eventType").value("RECRUITER_ACCEPTED"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("data")
            .path("content")
            .get(0);
    long id = inbox.path("id").asLong();
    http.perform(patch("/api/me/notifications/" + id + "/read").header("Authorization", recipient))
        .andExpect(status().isNotFound());
    var read =
        mapper
            .readTree(
                http.perform(
                        patch("/api/me/notifications/" + id + "/read")
                            .header("Authorization", owner))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("data");
    clock.now = clock.now.plusSeconds(100);
    http.perform(patch("/api/me/notifications/" + id + "/read").header("Authorization", owner))
        .andExpect(jsonPath("$.data.readAt").value(read.path("readAt").asText()));
  }

  @Test
  void malformedSecretsRemainPrivateUnavailableResponses() throws Exception {
    String recipient = account();
    for (String action : new String[] {"preview", "accept", "decline"}) {
      for (String body : new String[] {"{}", "{\"secret\":null}", "{\"secret\":\"\"}",
          mapper.writeValueAsString(Map.of("secret", " ")),
          mapper.writeValueAsString(Map.of("secret", "x".repeat(129), "confirm", false))}) {
        http.perform(post("/api/company-invitations/" + action)
                .header("Authorization", recipient)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message")
                .value("40410 INVITATION_UNAVAILABLE: Invitation unavailable"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("x".repeat(129)))));
      }
    }
  }

  @Test
  void emailPossessionAndAccountIdentityAreBothRequiredAndUnavailableErrorsHideState()
      throws Exception {
    String owner = account(), recipient = account(), stranger = account();
    long company = verifiedCompany(owner);
    String secret = invite(owner, email(recipient));
    database.update("UPDATE users SET email_verified=true WHERE id=?", userId(stranger));
    for (String action : new String[] {"preview", "accept", "decline"}) {
      secretPost(action, stranger, secret, true)
          .andExpect(status().isNotFound())
          .andExpect(
              jsonPath("$.message").value("40410 INVITATION_UNAVAILABLE: Invitation unavailable"));
      secretPost(action, recipient, "invalid-secret", true).andExpect(status().isNotFound());
      http.perform(
              post("/api/company-invitations/" + action)
                  .header("Authorization", recipient)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isNotFound())
          .andExpect(header().string("Cache-Control", "no-store"));
    }
    database.update("UPDATE users SET email_verified=false WHERE id=?", userId(recipient));
    secretPost("preview", recipient, secret, null).andExpect(status().isOk());
    database.update("UPDATE companies SET verification_status='PENDING' WHERE id=?", company);
    secretPost("accept", recipient, secret, true).andExpect(status().isNotFound());
    database.update(
        "UPDATE companies SET verification_status='VERIFIED',is_banned=true WHERE id=?", company);
    secretPost("accept", recipient, secret, true).andExpect(status().isNotFound());
    database.update("UPDATE companies SET is_banned=false WHERE id=?", company);
    secretPost("accept", recipient, secret, true).andExpect(status().isCreated());
  }

  @Test
  void recipientCanRegisterAfterDeliveryAndDeclineReceiptIsIdempotent() throws Exception {
    String owner = account();
    verifiedCompany(owner);
    String invited = "new_" + UUID.randomUUID() + "@example.vn";
    String secret = invite(owner, invited);
    String recipient = account(invited);
    secretPost("preview", recipient, secret, null).andExpect(status().isOk());
    var receipt =
        mapper
            .readTree(
                secretPost("decline", recipient, secret, null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("DECLINED"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("data");
    clock.now = clock.now.plusSeconds(30);
    secretPost("decline", recipient, secret, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.decidedAt").value(receipt.path("decidedAt").asText()));
    secretPost("accept", recipient, secret, true).andExpect(status().isNotFound());
    secretPost("preview", recipient, secret, null).andExpect(status().isNotFound());
    http.perform(get("/api/auth/me").header("Authorization", recipient))
        .andExpect(jsonPath("$.data.activeMembership").isEmpty());
    http.perform(get("/api/me/notifications").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(0));
  }

  @Test
  void expiredRevokedAndRotatedSecretsNeverActivateMembership() throws Exception {
    String owner = account(), recipient = account();
    verifiedCompany(owner);
    String first = invite(owner, email(recipient));
    long id = lastInvitation(owner);
    http.perform(
            post("/api/companies/me/invitations/" + id + "/resend").header("Authorization", owner))
        .andExpect(status().isOk());
    String current = mailer.secrets.getLast();
    secretPost("accept", recipient, first, true).andExpect(status().isNotFound());
    http.perform(delete("/api/companies/me/invitations/" + id).header("Authorization", owner))
        .andExpect(status().isOk());
    secretPost("accept", recipient, current, true).andExpect(status().isNotFound());
    String expiring = invite(owner, email(recipient));
    clock.now = clock.now.plus(java.time.Duration.ofDays(7));
    secretPost("preview", recipient, expiring, null).andExpect(status().isNotFound());
    secretPost("accept", recipient, expiring, true).andExpect(status().isNotFound());
    http.perform(get("/api/auth/me").header("Authorization", recipient))
        .andExpect(jsonPath("$.data.activeMembership").isEmpty());
  }

  @Test
  void acceptedRetryReturnsCurrentInactivePeriodAndCannotRestoreWorkspace() throws Exception {
    String owner = account(), recipient = account();
    verifiedCompany(owner);
    String secret = invite(owner, email(recipient));
    var receipt =
        mapper
            .readTree(
                secretPost("accept", recipient, secret, true)
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("data");
    long membership = receipt.path("membershipId").asLong();
    database.update(
        "UPDATE company_memberships SET status='REMOVED',ended_at=?,ended_by_user_id=? WHERE id=?",
        java.sql.Timestamp.from(clock.now),
        userId(owner),
        membership);
    clock.now = clock.now.plus(java.time.Duration.ofDays(10));
    secretPost("accept", recipient, secret, true)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.membershipId").value(membership))
        .andExpect(jsonPath("$.data.membershipStatus").value("REMOVED"))
        .andExpect(jsonPath("$.data.acceptedAt").value(receipt.path("acceptedAt").asText()));
    http.perform(get("/api/auth/me").header("Authorization", recipient))
        .andExpect(jsonPath("$.data.activeMembership").isEmpty());
    http.perform(
            get("/api/companies/" + receipt.path("companyId").asLong())
                .header("Authorization", recipient))
        .andExpect(status().isNotFound());
    http.perform(get("/api/me/notifications").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(1));
  }

  @Test
  void concurrentSameInvitationHasOneMembershipAndNotification() throws Exception {
    String owner = account(), recipient = account();
    verifiedCompany(owner);
    String secret = invite(owner, email(recipient));
    CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var a = workers.submit(() -> racingAccept(recipient, secret, ready, start));
      var b = workers.submit(() -> racingAccept(recipient, secret, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 200);
    }
    http.perform(get("/api/me/notifications").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(1));
  }

  @Test
  void concurrentCompaniesCannotAdmitSameUserTwiceAndConflictIsPrivate() throws Exception {
    String owner = account(), secondOwner = account(), recipient = account();
    verifiedCompany(owner);
    verifiedCompany(secondOwner);
    String aSecret = invite(owner, email(recipient)),
        bSecret = invite(secondOwner, email(recipient));
    CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var a = workers.submit(() -> racingAccept(recipient, aSecret, ready, start));
      var b = workers.submit(() -> racingAccept(recipient, bSecret, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 409);
    }
    String conflict =
        secretPost("accept", recipient, aSecret, true)
            .andReturn()
            .getResponse()
            .getContentAsString();
    if (!conflict.contains("40910"))
      conflict =
          secretPost("accept", recipient, bSecret, true)
              .andReturn()
              .getResponse()
              .getContentAsString();
    assertThat(conflict)
        .contains("40910 ACTIVE_COMPANY_MEMBERSHIP_EXISTS")
        .doesNotContain("companyId", "companyName");
  }

  @Test
  void failedPostCommitInboxDispatchLeavesMembershipActiveAndRecoveryCreatesExactlyOneItem()
      throws Exception {
    String owner = account(), recipient = account();
    verifiedCompany(owner);
    long ownerId = userId(owner);
    String secret = invite(owner, email(recipient));
    database.execute(
        "CREATE FUNCTION gw006_reject_inbox() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION"
            + " 'controlled inbox failure' USING ERRCODE='23514'; END; $$ LANGUAGE plpgsql");
    database.execute(
        "CREATE TRIGGER gw006_reject_inbox BEFORE INSERT ON user_notifications FOR EACH ROW WHEN"
            + " (NEW.recipient_user_id="
            + ownerId
            + ") EXECUTE FUNCTION gw006_reject_inbox()");
    java.util.UUID event;
    try {
      secretPost("accept", recipient, secret, true).andExpect(status().isCreated());
      http.perform(get("/api/auth/me").header("Authorization", recipient))
          .andExpect(jsonPath("$.data.activeMembership.companyRole").value("RECRUITER"));
      secretPost("accept", recipient, secret, true).andExpect(status().isOk());
      http.perform(get("/api/me/notifications").header("Authorization", owner))
          .andExpect(jsonPath("$.data.totalElements").value(0));
      event =
          database.queryForObject(
              "SELECT event_id FROM notification_outbox WHERE recipient_user_id=?",
              java.util.UUID.class,
              ownerId);
      // Operational metadata is part of the approved persistence/recovery seam.
      assertThat(
              database.queryForObject(
                  "SELECT attempt_count FROM notification_outbox WHERE event_id=?",
                  Integer.class,
                  event))
          .isEqualTo(1);
    } finally {
      database.execute("DROP TRIGGER gw006_reject_inbox ON user_notifications");
      database.execute("DROP FUNCTION gw006_reject_inbox()");
    }
    dispatcher.dispatchDue();
    http.perform(get("/api/me/notifications").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(0));
    clock.now = clock.now.plusSeconds(30);
    dispatcher.dispatchDue();
    dispatcher.replay(event);
    dispatcher.dispatch(event);
    http.perform(get("/api/me/notifications").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(1));
    secretPost("accept", recipient, secret, true).andExpect(status().isOk());
    http.perform(get("/api/me/notifications").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(1));
  }

  @Test
  void auditFailureRollsBackAdmissionAndNotificationIntentTogether() throws Exception {
    String owner = account(), recipient = account();
    verifiedCompany(owner);
    long recipientId = userId(recipient);
    String secret = invite(owner, email(recipient));
    database.execute(
        "CREATE FUNCTION gw006_reject_audit() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION"
            + " 'controlled admission audit failure' USING ERRCODE='23514'; END; $$ LANGUAGE"
            + " plpgsql");
    database.execute(
        "CREATE TRIGGER gw006_reject_audit BEFORE INSERT ON company_membership_audit_events FOR"
            + " EACH ROW WHEN (NEW.actor_user_id="
            + recipientId
            + ") EXECUTE FUNCTION gw006_reject_audit()");
    try {
      secretPost("accept", recipient, secret, true).andExpect(status().isInternalServerError());
      http.perform(get("/api/auth/me").header("Authorization", recipient))
          .andExpect(jsonPath("$.data.activeMembership").isEmpty());
      http.perform(get("/api/me/notifications").header("Authorization", owner))
          .andExpect(jsonPath("$.data.totalElements").value(0));
      secretPost("preview", recipient, secret, null).andExpect(status().isOk());
    } finally {
      database.execute("DROP TRIGGER gw006_reject_audit ON company_membership_audit_events");
      database.execute("DROP FUNCTION gw006_reject_audit()");
    }
    secretPost("accept", recipient, secret, true).andExpect(status().isCreated());
  }

  @Test
  void inboxRequiresActiveAccountButNoMembershipAndRejectsOtherIdsOrInvalidPages()
      throws Exception {
    String user = account();
    http.perform(get("/api/me/notifications").header("Authorization", user))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));
    http.perform(
            patch("/api/me/notifications/9223372036854775807/read").header("Authorization", user))
        .andExpect(status().isNotFound());
    for (String query : new String[] {"?page=-1", "?size=0", "?size=101", "?page=oops"})
      http.perform(get("/api/me/notifications" + query).header("Authorization", user))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.status").value(400));
    http.perform(get("/api/me/notifications")).andExpect(status().isUnauthorized());
    long id = userId(user);
    database.update("UPDATE users SET status='SUSPENDED' WHERE id=?", id);
    http.perform(get("/api/me/notifications").header("Authorization", user))
        .andExpect(status().isForbidden());
  }

  long lastInvitation(String owner) throws Exception {
    return mapper
        .readTree(
            http.perform(get("/api/companies/me/invitations").header("Authorization", owner))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("data")
        .path("content")
        .get(0)
        .path("id")
        .asLong();
  }

  int racingAccept(String actor, String secret, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("race did not start");
    return secretPost("accept", actor, secret, true).andReturn().getResponse().getStatus();
  }

  @Test
  void previewAndDeclineRemainAvailableWhenCompanyVerificationChangesWithoutGrantingMembership()
      throws Exception {
    String owner = account(), recipient = account();
    long company = verifiedCompany(owner);
    String secret = invite(owner, email(recipient));
    database.update("UPDATE companies SET verification_status='PENDING' WHERE id=?", company);
    secretPost("preview", recipient, secret, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.companyRole").value("RECRUITER"));
    secretPost("accept", recipient, secret, true).andExpect(status().isNotFound());
    secretPost("decline", recipient, secret, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("DECLINED"));
    http.perform(get("/api/auth/me").header("Authorization", recipient))
        .andExpect(jsonPath("$.data.activeMembership").isEmpty());
  }

  @Test
  void mailboxRecipientCanRegisterAfterInvitationThenExplicitlyAccept() throws Exception {
    String owner = account();
    long company = verifiedCompany(owner);
    String invited = "new_accept_" + UUID.randomUUID() + "@example.vn";
    String secret = invite(owner, invited);
    String recipient = account(invited);
    secretPost("preview", recipient, secret, null).andExpect(status().isOk());
    secretPost("accept", recipient, secret, true).andExpect(status().isCreated());
    http.perform(get("/api/auth/me").header("Authorization", recipient))
        .andExpect(jsonPath("$.data.activeMembership.companyId").value(company));
    http.perform(get("/api/me/notifications").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(1));
  }

  @Test
  void persistentDispatchFailuresBackOffCapAndOperatorReplayRetainsOneEvent() throws Exception {
    String owner = account(), recipient = account();
    verifiedCompany(owner);
    long ownerId = userId(owner);
    String secret = invite(owner, email(recipient));
    database.execute(
        "CREATE FUNCTION gw006_reject_retry() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION"
            + " 'controlled retry failure' USING ERRCODE='23514'; END; $$ LANGUAGE plpgsql");
    database.execute(
        "CREATE TRIGGER gw006_reject_retry BEFORE INSERT ON user_notifications FOR EACH ROW WHEN"
            + " (NEW.recipient_user_id="
            + ownerId
            + ") EXECUTE FUNCTION gw006_reject_retry()");
    java.util.UUID event;
    try {
      secretPost("accept", recipient, secret, true).andExpect(status().isCreated());
      event =
          database.queryForObject(
              "SELECT event_id FROM notification_outbox WHERE recipient_user_id=?",
              java.util.UUID.class,
              ownerId);
      for (long expectedDelay : new long[] {30, 60, 120, 240, 480, 960, 1800, 1800}) {
        var next =
            database
                .queryForObject(
                    "SELECT next_attempt_at FROM notification_outbox WHERE event_id=?",
                    java.sql.Timestamp.class,
                    event)
                .toInstant();
        assertThat(java.time.Duration.between(clock.now, next).getSeconds())
            .isEqualTo(expectedDelay);
        assertThat(
                database.queryForObject(
                    "SELECT error_category FROM notification_outbox WHERE event_id=?",
                    String.class,
                    event))
            .isEqualTo("INBOX_WRITE_FAILED");
        clock.now = next;
        dispatcher.dispatchDue();
      }
      http.perform(get("/api/auth/me").header("Authorization", recipient))
          .andExpect(jsonPath("$.data.activeMembership.companyRole").value("RECRUITER"));
    } finally {
      database.execute("DROP TRIGGER gw006_reject_retry ON user_notifications");
      database.execute("DROP FUNCTION gw006_reject_retry()");
    }
    dispatcher.replay(event);
    dispatcher.replay(event);
    http.perform(get("/api/me/notifications").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(1));
  }

  @Test
  void confirmationValidationCannotRevealValidInvitationToWrongEmail() throws Exception {
    String owner = account(), recipient = account(), stranger = account();
    verifiedCompany(owner);
    String secret = invite(owner, email(recipient));
    secretPost("accept", stranger, secret, false)
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.message").value("40410 INVITATION_UNAVAILABLE: Invitation unavailable"));
    secretPost("accept", recipient, secret, false).andExpect(status().isBadRequest());
  }

  org.springframework.test.web.servlet.ResultActions secretPost(
      String action, String token, String secret, Boolean confirm) throws Exception {
    var body = new java.util.HashMap<String, Object>();
    body.put("secret", secret);
    if (confirm != null) body.put("confirm", confirm);
    return http.perform(
        post("/api/company-invitations/" + action)
            .header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(body)));
  }

  String email(String token) throws Exception {
    return mapper
        .readTree(
            http.perform(get("/api/auth/me").header("Authorization", token))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("data")
        .path("email")
        .asText();
  }

  String invite(String owner, String email) throws Exception {
    http.perform(
            post("/api/companies/me/invitations")
                .header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email))))
        .andExpect(status().isCreated());
    return mailer.secrets.getLast();
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
    volatile java.time.Instant now = java.time.Instant.parse("2026-10-08T16:59:59Z");

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
                completeDossier("POST", "/api/companies/verification-request", company(UUID.randomUUID().toString()))
                    .header("Authorization", token)

                    )
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
    database.update("UPDATE users SET recruiting_email_verification_required=FALSE WHERE email=?", email);
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
