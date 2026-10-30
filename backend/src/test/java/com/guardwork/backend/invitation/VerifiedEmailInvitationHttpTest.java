package com.guardwork.backend.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.invitation.mail.CompanyInvitationMailer;
import com.guardwork.backend.invitation.model.DeliveryStatus;
import com.guardwork.backend.invitation.dto.RecipientInvitationRequest;
import com.guardwork.backend.invitation.service.RecipientInvitationService;
import com.guardwork.backend.onboarding.delivery.OnboardingDelivery;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Import(VerifiedEmailInvitationHttpTest.TimeConfiguration.class)
class VerifiedEmailInvitationHttpTest {
  @Autowired WebApplicationContext context;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate database;
  @Autowired PlatformTransactionManager transactions;
  @Autowired MutableClock clock;
  @Autowired RecipientInvitationService recipients;
  @MockitoBean CompanyInvitationMailer invitationMail;
  @MockitoBean OnboardingDelivery accountMail;
  final Map<String, String> invitationSecrets = new ConcurrentHashMap<>();
  final Map<String, String> accountSecrets = new ConcurrentHashMap<>();
  MockMvc http;

  @BeforeEach
  void setup() {
    clock.now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    invitationSecrets.clear();
    accountSecrets.clear();
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(invitationMail.send(anyString(), anyString(), any(), anyString()))
        .thenAnswer(call -> {
          invitationSecrets.put(call.getArgument(0), call.getArgument(3));
          return DeliveryStatus.PROVIDER_ACCEPTED;
        });
    when(accountMail.memberEmail(anyString(), anyString())).thenAnswer(call -> {
      accountSecrets.put(call.getArgument(0), call.getArgument(1));
      return true;
    });
  }

  @Test
  void newRecipientMustProveAccountEmailBeforeJoiningAndPreviewGrantsNoProof() throws Exception {
    CompanyFixture company = existingVerifiedCompany();
    Account recipient = account();
    String secret = invite(company.owner(), recipient.email());
    invitation("preview", recipient, secret, null).andExpect(status().isOk());
    currentUser(recipient).andExpect(jsonPath("$.data.emailVerified").value(false))
        .andExpect(jsonPath("$.data.activeMembership").isEmpty());
    invitation("accept", recipient, secret, true).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(
            "40335 EMAIL_VERIFICATION_REQUIRED: Verify your account email before recruiting"));
    invitation("preview", recipient, secret, null).andExpect(status().isOk());
    currentUser(recipient).andExpect(jsonPath("$.data.activeMembership").isEmpty());
    inbox(company.owner()).andExpect(jsonPath("$.data.totalElements").value(0));

    verifyEmail(recipient);
    invitation("accept", recipient, secret, true).andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.companyId").value(company.id()))
        .andExpect(jsonPath("$.data.companyRole").value("RECRUITER"));
    currentUser(recipient).andExpect(jsonPath("$.data.emailVerified").value(true))
        .andExpect(jsonPath("$.data.activeMembership.companyId").value(company.id()));
    inbox(company.owner()).andExpect(jsonPath("$.data.totalElements").value(1));
  }

  @Test
  void retainedEmployerProfileDoesNotBlockSharedVerifiedEmailAcceptance() throws Exception {
    CompanyFixture company = existingVerifiedCompany();
    Account recipient = account();
    database.update("INSERT INTO employer_profiles(user_id,applicant,full_name) VALUES (?,true,'Retained applicant')", recipient.id());
    var retained = database.queryForMap("SELECT * FROM employer_profiles WHERE user_id=?", recipient.id());
    String secret = invite(company.owner(), recipient.email());
    verifyEmail(recipient);
    invitation("accept", recipient, secret, true).andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.companyId").value(company.id()));
    assertThat(database.queryForMap("SELECT * FROM employer_profiles WHERE user_id=?", recipient.id())).isEqualTo(retained);
  }

  @Test
  void unavailableInvitationsRemainNonEnumeratingBeforeEmailProof() throws Exception {
    CompanyFixture company = existingVerifiedCompany();
    Account recipient = account();
    Account stranger = account();
    String secret = invite(company.owner(), recipient.email());
    invitation("accept", stranger, secret, true).andExpect(status().isNotFound());
    invitation("accept", recipient, "invalid-secret", true).andExpect(status().isNotFound());

    var preview = body(invitation("preview", recipient, secret, null).andExpect(status().isOk()));
    clock.now = Instant.parse(preview.path("expiresAt").asText());
    invitation("accept", recipient, secret, true).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("40410 INVITATION_UNAVAILABLE: Invitation unavailable"));
    currentUser(recipient).andExpect(jsonPath("$.data.activeMembership").isEmpty());
    inbox(company.owner()).andExpect(jsonPath("$.data.totalElements").value(0));
  }

  @Test
  void everyRecipientRequiresEmailProofBeforeFirstAcceptance() throws Exception {
    CompanyFixture company = existingVerifiedCompany();
    Account recipient = account();
    database.update("INSERT INTO employer_profiles(user_id,applicant,full_name) VALUES (?,false,'Existing recipient')", recipient.id());
    String secret = invite(company.owner(), recipient.email());
    invitation("accept", recipient, secret, true).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("40335 EMAIL_VERIFICATION_REQUIRED: Verify your account email before recruiting"));
    currentUser(recipient).andExpect(jsonPath("$.data.emailVerified").value(false))
        .andExpect(jsonPath("$.data.recruitingEmailEligible").value(false))
        .andExpect(jsonPath("$.data.activeMembership").isEmpty());
    inbox(company.owner()).andExpect(jsonPath("$.data.totalElements").value(0));
    verifyEmail(recipient);
    invitation("accept", recipient, secret, true).andExpect(status().isCreated());
    currentUser(recipient).andExpect(jsonPath("$.data.emailVerified").value(true))
        .andExpect(jsonPath("$.data.activeMembership.companyId").value(company.id()));
    inbox(company.owner()).andExpect(jsonPath("$.data.totalElements").value(1));
  }

  @Test
  void serviceRechecksPersistedEmailEligibilityAndUnverifiedRecipientMayDecline() throws Exception {
    CompanyFixture company = existingVerifiedCompany();
    Account recipient = account();
    String secret = invite(company.owner(), recipient.email());
    verifyEmail(recipient);
    database.update("UPDATE users SET email_verified=false WHERE id=?", recipient.id());
    assertThatThrownBy(() -> recipients.accept(recipient.id(), new RecipientInvitationRequest(secret, true)))
        .isInstanceOfSatisfying(ResponseStatusException.class, error ->
            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
        .hasMessageContaining("40335 EMAIL_VERIFICATION_REQUIRED");
    invitation("preview", recipient, secret, null).andExpect(status().isOk());
    invitation("decline", recipient, secret, null).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("DECLINED"));
    currentUser(recipient).andExpect(jsonPath("$.data.emailVerified").value(false))
        .andExpect(jsonPath("$.data.activeMembership").isEmpty());
    inbox(company.owner()).andExpect(jsonPath("$.data.totalElements").value(0));
  }

  @Test
  void verifiedEmailDoesNotReplaceConsentOrCurrentCompanyAndInvitationEligibility() throws Exception {
    CompanyFixture company = existingVerifiedCompany();
    Account recipient = account();
    verifyEmail(recipient);
    String secret = invite(company.owner(), " " + recipient.email().toUpperCase(java.util.Locale.ROOT) + " ");
    var preview = body(invitation("preview", recipient, secret, null).andExpect(status().isOk()));
    invitation("accept", recipient, secret, false).andExpect(status().isBadRequest());
    database.update("UPDATE companies SET is_banned=true WHERE id=?", company.id());
    invitation("accept", recipient, secret, true).andExpect(status().isNotFound());
    database.update("UPDATE companies SET is_banned=false,verification_status='PENDING' WHERE id=?", company.id());
    invitation("accept", recipient, secret, true).andExpect(status().isNotFound());
    database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?", company.id());
    http.perform(delete("/api/companies/me/invitations/" + preview.path("invitationId").asLong())
        .header("Authorization", company.owner().authorization())).andExpect(status().isOk());
    invitation("accept", recipient, secret, true).andExpect(status().isNotFound());
    currentUser(recipient).andExpect(jsonPath("$.data.emailVerified").value(true))
        .andExpect(jsonPath("$.data.activeMembership").isEmpty());
    inbox(company.owner()).andExpect(jsonPath("$.data.totalElements").value(0));
  }

  @Test
  void competingAcceptancesGrantOneMembershipAndHistoricalReceiptNeverReadmitsRemovedRecipient() throws Exception {
    CompanyFixture first = existingVerifiedCompany();
    CompanyFixture second = existingVerifiedCompany();
    Account recipient = account();
    verifyEmail(recipient);
    String firstSecret = invite(first.owner(), recipient.email());
    String secondSecret = invite(second.owner(), recipient.email());
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    AcceptanceResponse firstResponse;
    AcceptanceResponse secondResponse;
    try (var workers = Executors.newFixedThreadPool(2)) {
      var firstAttempt = workers.submit(() -> racingAccept(recipient, firstSecret, ready, start));
      var secondAttempt = workers.submit(() -> racingAccept(recipient, secondSecret, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      firstResponse = firstAttempt.get(10, TimeUnit.SECONDS);
      secondResponse = secondAttempt.get(10, TimeUnit.SECONDS);
    }
    assertThat(java.util.List.of(firstResponse.status(), secondResponse.status()))
        .containsExactlyInAnyOrder(201, 409);
    boolean firstWon = firstResponse.status() == 201;
    var accepted = firstWon ? firstResponse.data() : secondResponse.data();
    var winner = firstWon ? first : second;
    String acceptedSecret = firstWon ? firstSecret : secondSecret;
    String pendingSecret = firstWon ? secondSecret : firstSecret;
    currentUser(recipient).andExpect(jsonPath("$.data.activeMembership.companyId").value(winner.id()));
    invitation("preview", recipient, pendingSecret, null).andExpect(status().isOk());
    invitation("accept", recipient, pendingSecret, true).andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(
            "40910 ACTIVE_COMPANY_MEMBERSHIP_EXISTS: Active Company membership already exists"));
    inbox(winner.owner()).andExpect(jsonPath("$.data.totalElements").value(1));
    inbox((firstWon ? second : first).owner()).andExpect(jsonPath("$.data.totalElements").value(0));

    long membershipId = accepted.path("membershipId").asLong();
    http.perform(delete("/api/companies/me/members/" + membershipId)
        .header("Authorization", winner.owner().authorization())).andExpect(status().isOk());
    database.update("UPDATE users SET email_verified=false WHERE id=?", recipient.id());
    invitation("accept", recipient, acceptedSecret, true).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.membershipId").value(membershipId))
        .andExpect(jsonPath("$.data.membershipStatus").value("REMOVED"));
    currentUser(recipient).andExpect(jsonPath("$.data.activeMembership").isEmpty());
    inbox(winner.owner()).andExpect(jsonPath("$.data.totalElements").value(1));
  }

  AcceptanceResponse racingAccept(Account recipient, String secret, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Acceptance race did not start");
    var response = invitation("accept", recipient, secret, true).andReturn().getResponse();
    return new AcceptanceResponse(response.getStatus(), json.readTree(response.getContentAsString()).path("data"));
  }

  Account account() throws Exception {
    String name = "email_invite_" + UUID.randomUUID().toString().replace("-", "");
    String email = name + "@example.vn";
    var registered = body(http.perform(post("/api/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("firstName", "Ánh", "lastName", "Nguyễn",
            "username", name, "email", email, "password", "Password123"))))
        .andExpect(status().isCreated()));
    var login = body(http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("usernameOrEmail", email, "password", "Password123"))))
        .andExpect(status().isOk()));
    return new Account(registered.path("id").asLong(), email,
        "Bearer " + login.path("accessToken").asText());
  }

  CompanyFixture existingVerifiedCompany() throws Exception {
    Account owner = account();
    // This retained Company is a fixture; recipient registration and mailbox proof use real APIs.
    database.update("UPDATE users SET email_verified=true WHERE id=?", owner.id());
    Long company = new TransactionTemplate(transactions).execute(tx -> {
      Long id = database.queryForObject("INSERT INTO companies(name,tax_code,email,"
          + "registration_certificate_url,verification_status,owner_user_id) VALUES (?,?,?,?,?,?) RETURNING id",
          Long.class, "Công ty đã xác minh", UUID.randomUUID().toString(), owner.email(),
          "https://example.test/retained.pdf", "VERIFIED", owner.id());
      database.update("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at)"
          + " VALUES (?,?,'OWNER','ACTIVE',?)", id, owner.id(), Timestamp.from(clock.instant()));
      return id;
    });
    return new CompanyFixture(company, owner);
  }

  String invite(Account owner, String email) throws Exception {
    http.perform(post("/api/companies/me/invitations").header("Authorization", owner.authorization())
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", email))))
        .andExpect(status().isCreated());
    return invitationSecrets.get(email.trim().toLowerCase(java.util.Locale.ROOT));
  }

  void verifyEmail(Account account) throws Exception {
    http.perform(post("/api/me/email-verification/request").header("Authorization", account.authorization()))
        .andExpect(status().isOk());
    http.perform(post("/api/me/email-verification/confirm").header("Authorization", account.authorization())
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("emailSecret", accountSecrets.get(account.email())))))
        .andExpect(status().isOk());
  }

  ResultActions invitation(String action, Account actor, String secret, Boolean confirm) throws Exception {
    var request = new java.util.HashMap<String, Object>();
    request.put("secret", secret);
    if (confirm != null) request.put("confirm", confirm);
    return http.perform(post("/api/company-invitations/" + action)
        .header("Authorization", actor.authorization()).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(request)));
  }

  ResultActions currentUser(Account actor) throws Exception {
    return http.perform(get("/api/auth/me").header("Authorization", actor.authorization())).andExpect(status().isOk());
  }

  ResultActions inbox(Account actor) throws Exception {
    return http.perform(get("/api/me/notifications").header("Authorization", actor.authorization())).andExpect(status().isOk());
  }

  JsonNode body(ResultActions response) throws Exception {
    return json.readTree(response.andReturn().getResponse().getContentAsString()).path("data");
  }

  record Account(long id, String email, String authorization) {}
  record CompanyFixture(long id, Account owner) {}
  record AcceptanceResponse(int status, JsonNode data) {}

  @TestConfiguration
  static class TimeConfiguration {
    @Bean @Primary MutableClock invitationEmailClock() { return new MutableClock(); }
  }

  static class MutableClock extends Clock {
    volatile Instant now = Instant.now();
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }
}
