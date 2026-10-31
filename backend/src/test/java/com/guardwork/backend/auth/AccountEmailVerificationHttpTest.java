package com.guardwork.backend.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.onboarding.delivery.OnboardingDelivery;
import java.util.Map;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Import(AccountEmailVerificationHttpTest.TimeConfig.class)
class AccountEmailVerificationHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired org.springframework.jdbc.core.JdbcTemplate database;
    @MockitoBean OnboardingDelivery delivery;
    MockMvc http;

    @BeforeEach void setup() {
        clock.now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void explicitRequestDeliversOnlyToAuthenticatedAccountAndReturnsNoSecret() throws Exception {
        JsonNode login = registerAndLogin();
        when(delivery.memberEmail(anyString(), anyString())).thenReturn(true);
        String response = http.perform(post("/api/me/email-verification/request")
                .header("Authorization", "Bearer " + login.path("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deliveryAccepted").value(true))
                .andExpect(jsonPath("$.data.alreadyVerified").value(false))
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andReturn().getResponse().getContentAsString();
        var secret = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(delivery).memberEmail(eq(login.path("user").path("email").asText()), secret.capture());
        assertThat(secret.getValue()).matches("[0-9]{6}");
        assertThat(response).doesNotContain(secret.getValue());
    }

    @Test void confirmationVerifiesOnlyOnceAndDoesNotNeedACompany() throws Exception {
        JsonNode login = registerAndLogin();
        String authorization = "Bearer " + login.path("accessToken").asText();
        when(delivery.memberEmail(anyString(), anyString())).thenReturn(true);
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization))
                .andExpect(status().isOk());
        var secret = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(delivery).memberEmail(anyString(), secret.capture());
        String proof = json.writeValueAsString(Map.of("emailSecret", secret.getValue()));
        http.perform(post("/api/me/email-verification/confirm").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(proof))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.emailVerified").value(true));
        http.perform(get("/api/auth/me").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.emailVerified").value(true))
                .andExpect(jsonPath("$.data.recruitingEmailEligible").value(true));
        http.perform(post("/api/me/email-verification/confirm").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(proof))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(
                        "40030 INVALID_EMAIL_PROOF: Invalid or expired email proof"));
    }

    @Test void resendAtTheExactCooldownInvalidatesPriorProofEvenWhenDeliveryFails() throws Exception {
        JsonNode login = registerAndLogin();
        String authorization = "Bearer " + login.path("accessToken").asText();
        var secrets = new java.util.ArrayList<String>();
        when(delivery.memberEmail(anyString(), anyString())).thenAnswer(call -> {
            secrets.add(call.getArgument(1));
            return secrets.size() == 1;
        });
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization))
                .andExpect(status().isOk());
        clock.now = clock.now.plusSeconds(59);
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.message").value("42930 EMAIL_REQUEST_COOLDOWN: Wait before requesting another verification email"));
        clock.now = clock.now.plusSeconds(1);
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deliveryAccepted").value(false));
        http.perform(post("/api/me/email-verification/confirm").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("emailSecret", secrets.getFirst()))))
                .andExpect(status().isBadRequest());
        clock.now = clock.now.plusSeconds(60);
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization))
                .andExpect(status().isOk());
        assertThat(secrets).hasSize(3).doesNotHaveDuplicates();
        http.perform(post("/api/me/email-verification/confirm").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("emailSecret", secrets.getLast()))))
                .andExpect(status().isOk());
    }

    @Test void exactExpiryAndWrongAccountCannotConsumeTheRecipientsProof() throws Exception {
        JsonNode recipient = registerAndLogin();
        JsonNode wrongAccount = registerAndLogin();
        when(delivery.memberEmail(anyString(), anyString())).thenReturn(true);
        http.perform(post("/api/me/email-verification/request")
                .header("Authorization", "Bearer " + recipient.path("accessToken").asText())).andExpect(status().isOk());
        var secret = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(delivery).memberEmail(anyString(), secret.capture());
        String body = json.writeValueAsString(Map.of("emailSecret", secret.getValue()));
        http.perform(post("/api/me/email-verification/confirm")
                .header("Authorization", "Bearer " + wrongAccount.path("accessToken").asText())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        clock.now = clock.now.plusSeconds(600);
        http.perform(post("/api/me/email-verification/confirm")
                .header("Authorization", "Bearer " + recipient.path("accessToken").asText())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        http.perform(get("/api/auth/me").header("Authorization", "Bearer " + recipient.path("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.emailVerified").value(false));
    }

    @Test void wrongAccountRejectionLeavesTheRecipientsProofUsable() throws Exception {
        JsonNode recipient = registerAndLogin();
        JsonNode wrongAccount = registerAndLogin();
        http.perform(post("/api/me/email-verification/request")
                .header("Authorization", "Bearer " + recipient.path("accessToken").asText())).andExpect(status().isOk());
        var secret = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(delivery).memberEmail(anyString(), secret.capture());
        String body = json.writeValueAsString(Map.of("emailSecret", secret.getValue()));
        http.perform(post("/api/me/email-verification/confirm")
                .header("Authorization", "Bearer " + wrongAccount.path("accessToken").asText())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        http.perform(post("/api/me/email-verification/confirm")
                .header("Authorization", "Bearer " + recipient.path("accessToken").asText())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void deliveryExceptionDoesNotUndoIssuanceOrGrantProofAndCanRecoverAfterCooldown(CapturedOutput output) throws Exception {
        JsonNode login = registerAndLogin();
        String authorization = "Bearer " + login.path("accessToken").asText();
        var attemptedSecret = new java.util.concurrent.atomic.AtomicReference<String>();
        when(delivery.memberEmail(anyString(), anyString())).thenAnswer(call -> {
            attemptedSecret.set(call.getArgument(1));
            throw new IllegalStateException("provider-private-detail email=" + call.getArgument(0)
                    + " token=" + call.getArgument(1));
        });
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deliveryAccepted").value(false));
        assertThat(output.getAll()).contains("event=handled_failure", "operation=handle_failure",
                "error_code=LOG:UNEXPECTED_FAILURE", "java.lang.IllegalStateException", "\tat ")
                .doesNotContain("provider-private-detail", login.path("user").path("email").asText(), attemptedSecret.get());
        http.perform(get("/api/auth/me").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.emailVerified").value(false));
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization))
                .andExpect(status().isTooManyRequests());
        clock.now = clock.now.plusSeconds(60);
        doReturn(true).when(delivery).memberEmail(anyString(), anyString());
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deliveryAccepted").value(true));
    }

    @Test void emailVerificationControlsRecruitingEligibilityButNotCandidateAccess() throws Exception {
        for (boolean verified : new boolean[]{false, true}) {
            JsonNode login = registerAndLogin();
            long id = login.path("user").path("id").asLong();
            database.update("UPDATE users SET email_verified=? WHERE id=?", verified, id);
            database.update("INSERT INTO employer_profiles(user_id,applicant,full_name) VALUES (?,TRUE,'Existing applicant')", id);
            String authorization = "Bearer " + login.path("accessToken").asText();
            http.perform(get("/api/auth/me").header("Authorization", authorization))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.emailVerified").value(verified))
                    .andExpect(jsonPath("$.data.recruitingEmailVerificationRequired").doesNotExist())
                    .andExpect(jsonPath("$.data.candidateAccessAllowed").doesNotExist())
                    .andExpect(jsonPath("$.data.recruitingEmailEligible").value(verified));
            http.perform(get("/api/v1/candidates/profile").header("Authorization", authorization))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(id));
        }
    }

    @Test void proofRoutesRejectAnonymousSuspendedAndStaffUsersWithoutDelivery() throws Exception {
        http.perform(post("/api/me/email-verification/request")).andExpect(status().isUnauthorized());
        JsonNode login = registerAndLogin();
        String authorization = "Bearer " + login.path("accessToken").asText();
        long id = login.path("user").path("id").asLong();
        database.update("UPDATE users SET status='SUSPENDED' WHERE id=?", id);
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization))
                .andExpect(status().isUnauthorized());
        database.update("UPDATE users SET status='ACTIVE',role='STAFF' WHERE id=?", id);
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization))
                .andExpect(status().isForbidden());
        verifyNoInteractions(delivery);
    }

    @Test void malformedChangedEmailAndAlreadyVerifiedRequestsRemainSafe() throws Exception {
        JsonNode login = registerAndLogin();
        String authorization = "Bearer " + login.path("accessToken").asText();
        long id = login.path("user").path("id").asLong();
        http.perform(post("/api/me/email-verification/confirm").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(
                        "40030 INVALID_EMAIL_PROOF: Invalid or expired email proof"));
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization)).andExpect(status().isOk());
        var secret = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(delivery).memberEmail(anyString(), secret.capture());
        database.update("UPDATE users SET email=? WHERE id=?", "changed_" + UUID.randomUUID() + "@example.vn", id);
        http.perform(post("/api/me/email-verification/confirm").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("emailSecret", secret.getValue()))))
                .andExpect(status().isBadRequest());
        database.update("UPDATE users SET email_verified=TRUE WHERE id=?", id);
        clearInvocations(delivery);
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.alreadyVerified").value(true))
                .andExpect(jsonPath("$.data.nextRequestAt").doesNotExist());
        verifyNoInteractions(delivery);
    }

    @Test void concurrentConfirmationsHaveExactlyOneWinner() throws Exception {
        JsonNode login = registerAndLogin();
        String authorization = "Bearer " + login.path("accessToken").asText();
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization)).andExpect(status().isOk());
        var secret = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(delivery).memberEmail(anyString(), secret.capture());
        String body = json.writeValueAsString(Map.of("emailSecret", secret.getValue()));
        assertThat(race(() -> http.perform(post("/api/me/email-verification/confirm").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus()))
                .containsExactlyInAnyOrder(200, 400);
        http.perform(get("/api/auth/me").header("Authorization", authorization)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailVerified").value(true));
    }

    @Test void concurrentRequestsIssueOnlyOneProofAndOneDelivery() throws Exception {
        JsonNode login = registerAndLogin();
        String authorization = "Bearer " + login.path("accessToken").asText();
        assertThat(race(() -> http.perform(post("/api/me/email-verification/request").header("Authorization", authorization))
                .andReturn().getResponse().getStatus())).containsExactlyInAnyOrder(200, 429);
        verify(delivery, times(1)).memberEmail(anyString(), anyString());
    }

    @Test void concurrentRegistrationsReturnOneCreatedUserAndTheDocumentedConflict() throws Exception {
        String username = "race_" + UUID.randomUUID().toString().replace("-", "");
        String body = json.writeValueAsString(Map.of("firstName", "Minh", "lastName", "Nguyễn", "username", username,
                "email", username + "@example.vn", "password", "GuardWork123!"));
        assertThat(race(() -> {
            var response = http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse();
            if (response.getStatus() == 409) assertThat(response.getContentAsString()).contains("40901 USERNAME_OR_EMAIL_EXISTS");
            return response.getStatus();
        })).containsExactlyInAnyOrder(201, 409);
        verifyNoInteractions(delivery);
    }

    @Test void fiveFailedGuessesInvalidateCodeAndResendResetsBudget() throws Exception {
        JsonNode login = registerAndLogin();
        String authorization = "Bearer " + login.path("accessToken").asText();
        long id = login.path("user").path("id").asLong();
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization))
                .andExpect(status().isOk());
        var codes = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(delivery).memberEmail(anyString(), codes.capture());
        String code = codes.getValue();
        String wrong = "000000".equals(code) ? "000001" : "000000";
        for (int i = 0; i < 5; i++) {
            http.perform(post("/api/me/email-verification/confirm").header("Authorization", authorization)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("emailSecret", wrong))))
                    .andExpect(status().isBadRequest());
        }
        assertThat(database.queryForObject("SELECT failed_attempts FROM user_email_verification WHERE user_id=?",
                Integer.class, id)).isEqualTo(5);
        http.perform(post("/api/me/email-verification/confirm").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("emailSecret", code))))
                .andExpect(status().isBadRequest());
        clock.now = clock.now.plusSeconds(60);
        clearInvocations(delivery);
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization))
                .andExpect(status().isOk());
        verify(delivery).memberEmail(anyString(), codes.capture());
        http.perform(post("/api/me/email-verification/confirm").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("emailSecret", codes.getValue()))))
                .andExpect(status().isOk());
    }

    @Test void malformedCodeConsumesAGuessButCorrectCodeStillWorksBeforeLimit() throws Exception {
        JsonNode login = registerAndLogin();
        String authorization = "Bearer " + login.path("accessToken").asText();
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization))
                .andExpect(status().isOk());
        var code = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(delivery).memberEmail(anyString(), code.capture());
        for (String malformed : new String[]{"12345", "1234567", "abcdef", "１２３４５６"}) {
            http.perform(post("/api/me/email-verification/confirm").header("Authorization", authorization)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("emailSecret", malformed))))
                    .andExpect(status().isBadRequest());
        }
        http.perform(post("/api/me/email-verification/confirm").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("emailSecret", code.getValue()))))
                .andExpect(status().isOk());
    }

    @Test void leadingZeroCodeWorksJustBeforeExpiry() throws Exception {
        JsonNode login = registerAndLogin();
        String authorization = "Bearer " + login.path("accessToken").asText();
        long id = login.path("user").path("id").asLong();
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization))
                .andExpect(status().isOk());
        database.update("UPDATE user_email_verification SET secret_hash=? WHERE user_id=?",
                com.guardwork.backend.onboarding.service.OnboardingSecrets.hash("012345"), id);
        clock.now = clock.now.plusSeconds(599);
        http.perform(post("/api/me/email-verification/confirm").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content("{\"emailSecret\":\"012345\"}"))
                .andExpect(status().isOk());
    }

    @Test void concurrentWrongGuessesDoNotLoseAttempts() throws Exception {
        JsonNode login = registerAndLogin();
        String authorization = "Bearer " + login.path("accessToken").asText();
        long id = login.path("user").path("id").asLong();
        http.perform(post("/api/me/email-verification/request").header("Authorization", authorization))
                .andExpect(status().isOk());
        assertThat(race(() -> http.perform(post("/api/me/email-verification/confirm")
                .header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content("{\"emailSecret\":\"invalid\"}"))
                .andReturn().getResponse().getStatus())).containsExactly(400, 400);
        assertThat(database.queryForObject("SELECT failed_attempts FROM user_email_verification WHERE user_id=?",
                Integer.class, id)).isEqualTo(2);
    }

    private java.util.List<Integer> race(java.util.concurrent.Callable<Integer> action) throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> contender = () -> {
                ready.countDown();
                if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return action.call();
            };
            var first = pool.submit(contender);
            var second = pool.submit(contender);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return java.util.List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS), second.get(15, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @TestConfiguration static class TimeConfig {
        @Bean @Primary MutableClock emailVerificationClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        volatile Instant now = Instant.now();
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }

    private JsonNode registerAndLogin() throws Exception {
        String username = "email_" + UUID.randomUUID().toString().replace("-", "");
        http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("firstName", "Minh", "lastName", "Nguyễn", "username", username,
                        "email", username + "@example.vn", "password", "GuardWork123!"))))
                .andExpect(status().isCreated());
        return json.readTree(http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("usernameOrEmail", username, "password", "GuardWork123!"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    @Test void sharedRegistrationSignsInWithoutAutomaticallyVerifyingOrSendingMail() throws Exception {
        String username = "email_" + UUID.randomUUID().toString().replace("-", "");
        var created = json.readTree(http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("firstName", "Minh", "lastName", "Nguyễn", "username", username,
                        "email", username + "@example.vn", "password", "GuardWork123!"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
        JsonNode login = json.readTree(http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("usernameOrEmail", username, "password", "GuardWork123!"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        http.perform(get("/api/auth/me").header("Authorization", "Bearer " + login.path("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.emailVerified").value(false))
                .andExpect(jsonPath("$.data.recruitingEmailVerificationRequired").doesNotExist())
                .andExpect(jsonPath("$.data.recruitingEmailEligible").value(false))
                .andExpect(jsonPath("$.data.candidateAccessAllowed").doesNotExist());
        http.perform(get("/api/v1/candidates/profile").header("Authorization", "Bearer " + login.path("accessToken").asText()))
                .andExpect(status().isOk());
        verifyNoInteractions(delivery);
    }
}
