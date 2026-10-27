package com.guardwork.backend.company;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.onboarding.delivery.OnboardingDelivery;
import com.guardwork.backend.invitation.service.InvitationSecrets;
import com.guardwork.backend.invitation.service.RecipientInvitationService;
import com.guardwork.backend.invitation.dto.RecipientInvitationRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.guardwork.backend.onboarding.repository.EmployerProfileRepository;
import com.guardwork.backend.onboarding.model.EmployerProfile;
import java.util.concurrent.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "employer.onboarding.delivery-worker-enabled=false")
class EmployerEmailEligibilityHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate database;
    @MockitoBean OnboardingDelivery delivery;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RecipientInvitationService invitations;
    @Autowired com.guardwork.backend.onboarding.service.LegalDossierService dossiers;
    @MockitoSpyBean EmployerProfileRepository profiles;
    final Map<String, String> secrets = new ConcurrentHashMap<>();
    MockMvc http;
    static final String ONBOARDING = "/api/me/employer-onboarding";
    record Account(long id, String email, String token) {}

    @BeforeEach
    void setup() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(delivery.memberEmail(anyString(), anyString())).thenAnswer(call -> {
            secrets.put(call.getArgument(0), call.getArgument(1));
            return true;
        });
    }

    @Test
    void legacyUserBooleanDoesNotUnlockAnEnrolledAccount() throws Exception {
        Account account = enrolledAccount();
        assertThat(database.queryForObject("SELECT email_verified FROM users WHERE id=?", Boolean.class, account.id())).isTrue();
        http.perform(get(ONBOARDING).header("Authorization", account.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.emailVerified").value(false));
        http.perform(post("/api/company-invitations/preview").header("Authorization", account.token())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        http.perform(get("/api/companies/me/verification/history").header("Authorization", account.token()))
                .andExpect(status().isForbidden());
        http.perform(put(ONBOARDING + "/company").header("Authorization", account.token())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void proofForThePreviousEmailCannotVerifyTheCurrentMailbox() throws Exception {
        Account account = enrolledAccount();
        send(account).andExpect(status().isOk());
        String secret = secrets.get(account.email());
        database.update("UPDATE users SET email=? WHERE id=?", UUID.randomUUID() + "@example.vn", account.id());
        confirm(account, secret).andExpect(status().isBadRequest());
        assertThat(database.queryForObject("SELECT email_verified_at FROM employer_profiles WHERE user_id=?",
                java.sql.Timestamp.class, account.id())).isNull();
        http.perform(get(ONBOARDING).header("Authorization", account.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.emailVerified").value(false));
    }

    @Test
    void proofRequiresTheCorrectAuthenticatedAccountAndIsSingleUse() throws Exception {
        Account owner = enrolledAccount();
        Account other = enrolledAccount();
        send(owner).andExpect(status().isOk());
        String secret = secrets.get(owner.email());
        confirm(other, secret).andExpect(status().isBadRequest());
        http.perform(post(ONBOARDING + "/email/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("emailSecret", secret))))
                .andExpect(status().isUnauthorized());
        confirm(owner, secret).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        confirm(owner, secret).andExpect(status().isBadRequest());
        http.perform(get(ONBOARDING).header("Authorization", owner.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.emailVerified").value(true))
                .andExpect(jsonPath("$.data.stage").value("COMPANY_ACTION_REQUIRED"));
    }

    @Test
    void aVerifiedSnapshotCannotUnlockAChangedEmailUntilNewProof() throws Exception {
        Account account = enrolledAccount();
        send(account).andExpect(status().isOk());
        confirm(account, secrets.get(account.email())).andExpect(status().isOk());
        String current = UUID.randomUUID() + "@example.vn";
        database.update("UPDATE users SET email=? WHERE id=?", current, account.id());
        http.perform(get(ONBOARDING).header("Authorization", account.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailVerified").value(false))
                .andExpect(jsonPath("$.data.stage").value("EMAIL_REQUIRED"));
        http.perform(put(ONBOARDING + "/company").header("Authorization", account.token())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        database.update("UPDATE employer_profiles SET email_sent_at=now()-interval '61 seconds' WHERE user_id=?", account.id());
        send(account).andExpect(status().isOk());
        confirm(account, secrets.get(current)).andExpect(status().isOk());
        assertThat(database.queryForObject("SELECT email_verified_email FROM employer_profiles WHERE user_id=?", String.class, account.id())).isEqualTo(current);
    }

    @Test
    void resendReplacesTheProofAndExpiredProofPreservesTheAccount() throws Exception {
        Account account = enrolledAccount();
        send(account).andExpect(status().isOk());
        String first = secrets.get(account.email());
        send(account).andExpect(status().isTooManyRequests());
        database.update("UPDATE employer_profiles SET email_sent_at=now()-interval '61 seconds' WHERE user_id=?", account.id());
        send(account).andExpect(status().isOk());
        assertThat(secrets.get(account.email())).isNotEqualTo(first);
        confirm(account, first).andExpect(status().isBadRequest());
        database.update("UPDATE employer_profiles SET email_expires_at=now()-interval '1 second' WHERE user_id=?", account.id());
        confirm(account, secrets.get(account.email())).andExpect(status().isBadRequest());
        assertThat(database.queryForObject("SELECT count(*) FROM users WHERE id=?", Long.class, account.id())).isEqualTo(1);
    }

    @Test
    void enrolledRecruiterAndCompanyAdminUnlockOnlyTheirExistingPermissions() throws Exception {
        Account owner = enrolledAccount();
        long company = verifiedCompany(owner);
        for (String role : new String[]{"RECRUITER", "ADMIN"}) {
            Account account = enrolledAccount();
            database.update("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?, ?, ?, 'ACTIVE', now())",
                    company, account.id(), role);
            http.perform(get("/api/companies/me").header("Authorization", account.token())).andExpect(status().isForbidden());
            send(account).andExpect(status().isOk());
            confirm(account, secrets.get(account.email())).andExpect(status().isOk());
            http.perform(get(ONBOARDING).header("Authorization", account.token())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.stage").value("VERIFIED"))
                    .andExpect(jsonPath("$.data.companyRole").value(role));
            http.perform(get("/api/companies/me").header("Authorization", account.token())).andExpect(status().isOk());
            http.perform(get("/api/me/notifications").header("Authorization", account.token())).andExpect(status().isOk());
            http.perform(get("/api/companies/me/members").header("Authorization", account.token())).andExpect(status().isForbidden());
            assertThat(database.queryForObject("SELECT role FROM company_memberships WHERE user_id=? AND status='ACTIVE'", String.class, account.id())).isEqualTo(role);
        }
    }

    @Test
    void invitationAdmissionEnforcesEmailProofInsideItsTransaction() throws Exception {
        Account owner = enrolledAccount();
        long company = verifiedCompany(owner);
        Account recipient = enrolledAccount();
        String secret = UUID.randomUUID().toString();
        database.update("INSERT INTO company_invitations(company_id,recipient_email_normalized,role,invited_by_user_id,status,secret_hash,issued_at,expires_at,created_at,updated_at,delivery_status) VALUES (?,?,'RECRUITER',?,'PENDING',?,now(),now()+interval '1 day',now(),now(),'UNKNOWN')",
                company, recipient.email(), owner.id(), InvitationSecrets.hash(secret));
        assertThatThrownBy(() -> invitations.accept(recipient.id(), new RecipientInvitationRequest(secret, true)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(403));
        assertThat(database.queryForObject("SELECT count(*) FROM company_memberships WHERE user_id=?", Long.class, recipient.id())).isZero();
        assertThat(database.queryForObject("SELECT status FROM company_invitations WHERE secret_hash=?", String.class, InvitationSecrets.hash(secret))).isEqualTo("PENDING");
        http.perform(post("/api/company-invitations/accept").header("Authorization", recipient.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("secret", secret, "confirm", true))))
                .andExpect(status().isForbidden());
        send(recipient).andExpect(status().isOk());
        confirm(recipient, secrets.get(recipient.email())).andExpect(status().isOk());
        for (int status : new int[]{201, 200}) {
            http.perform(post("/api/company-invitations/accept").header("Authorization", recipient.token())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("secret", secret, "confirm", true))))
                    .andExpect(status().is(status));
        }
        assertThat(database.queryForObject("SELECT count(*) FROM company_memberships WHERE user_id=?", Long.class, recipient.id())).isEqualTo(1);
    }

    @Test
    void newRegistrationIsRestrictedBeforeProofWithoutCompanyOrMembershipSideEffects() throws Exception {
        String email = UUID.randomUUID() + "@example.vn";
        MvcResult result = http.perform(post("/api/employer-registrations").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fullName", "Nguyễn An", "phone", "09%08d".formatted(Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000_000)),
                        "companyName", "Declared only", "gender", "OTHER", "workAddress", "Hà Nội", "email", email,
                        "password", "GuardWorkTest123!", "passwordConfirmation", "GuardWorkTest123!"))))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(result.getResponse().getContentAsString()).path("data").path("userId").asLong();
        MvcResult login = http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("usernameOrEmail", email, "password", "GuardWorkTest123!"))))
                .andExpect(status().isOk()).andReturn();
        Account account = new Account(id, email, "Bearer " + json.readTree(login.getResponse().getContentAsString()).path("data").path("accessToken").asText());
        http.perform(get("/api/auth/me").header("Authorization", account.token())).andExpect(status().isOk());
        http.perform(get(ONBOARDING).header("Authorization", account.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("EMAIL_REQUIRED"));
        for (String path : new String[]{"/api/companies/me", "/api/companies/me/verification/history", "/api/me/notifications", "/api/v1/candidates/profile"})
            http.perform(get(path).header("Authorization", account.token())).andExpect(status().isForbidden());
        http.perform(post("/api/companies/verification-request").header("Authorization", account.token())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(ONBOARDING + "/documents")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-fixture".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                .header("Authorization", account.token())).andExpect(status().isForbidden());
        assertThat(database.queryForObject("SELECT count(*) FROM companies WHERE owner_user_id=?", Long.class, id)).isZero();
        assertThat(database.queryForObject("SELECT count(*) FROM company_memberships WHERE user_id=?", Long.class, id)).isZero();
        assertThat(database.queryForObject("SELECT count(*) FROM company_verification_documents WHERE uploader_id=?", Long.class, id)).isZero();
        assertThat(database.queryForObject("SELECT email_verified FROM users WHERE id=?", Boolean.class, id)).isFalse();
        confirm(account, secrets.get(email)).andExpect(status().isOk());
        http.perform(put(ONBOARDING + "/company").header("Authorization", account.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("companyName", "Prepared", "workAddress", "Hà Nội", "expectedVersion", 1))))
                .andExpect(status().isOk());
        http.perform(get("/api/v1/candidates/profile").header("Authorization", account.token())).andExpect(status().isForbidden());
    }

    @Test
    void enrolledLegacyOwnerCannotBypassProofOrLegalCompletenessThroughCorrection() throws Exception {
        Account owner = enrolledAccount();
        long company = verifiedCompany(owner);
        database.update("UPDATE companies SET verification_status='REJECTED', rejection_reason='Fixture rejection' WHERE id=?", company);
        var correction = new com.guardwork.backend.company.dto.CompanyResubmissionRequest(
                "Must not change", owner.email(), "https://example.invalid/other.pdf", 0L);
        assertThatThrownBy(() -> dossiers.correct(owner.id(), company, correction))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(403));
        send(owner).andExpect(status().isOk());
        confirm(owner, secrets.get(owner.email())).andExpect(status().isOk());
        assertThatThrownBy(() -> dossiers.correct(owner.id(), company, correction))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        assertThat(database.queryForObject("SELECT verification_status FROM companies WHERE id=?", String.class, company)).isEqualTo("REJECTED");
        assertThat(database.queryForObject("SELECT name FROM companies WHERE id=?", String.class, company)).isEqualTo("Fixture");
        assertThat(database.queryForObject("SELECT count(*) FROM company_verification_attempts WHERE company_id=?", Long.class, company)).isZero();
    }

    @Test
    void aRacingSingleUseProofHasOneSuccessfulConfirmation() throws Exception {
        Account account = enrolledAccount();
        send(account).andExpect(status().isOk());
        String secret = secrets.get(account.email());
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Integer> confirm = () -> {
                start.await(10, TimeUnit.SECONDS);
                return confirm(account, secret).andReturn().getResponse().getStatus();
            };
            var first = pool.submit(confirm);
            var second = pool.submit(confirm);
            start.countDown();
            assertThat(java.util.List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 400);
        }
        assertThat(database.queryForObject("SELECT email_secret_hash FROM employer_profiles WHERE user_id=?", String.class, account.id())).isNull();
        assertThat(database.queryForObject("SELECT email_challenge_email FROM employer_profiles WHERE user_id=?", String.class, account.id())).isNull();
    }

    @Test
    void profileFailureRollsBackAccountAndIdentifierClaimsBeforeEmailDelivery() throws Exception {
        String email = UUID.randomUUID() + "@example.vn";
        String phone = "09%08d".formatted(Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000_000));
        doThrow(new IllegalStateException("test-only profile failure")).when(profiles)
                .saveAndFlush(argThat((EmployerProfile p) -> p != null && "Rollback Fixture".equals(p.getFullName())));
        http.perform(post("/api/employer-registrations").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fullName", "Rollback Fixture", "phone", phone,
                        "companyName", "Declared only", "gender", "OTHER", "workAddress", "Hà Nội", "email", email,
                        "password", "GuardWorkTest123!", "passwordConfirmation", "GuardWorkTest123!"))))
                .andExpect(status().isInternalServerError());
        assertThat(database.queryForObject("SELECT count(*) FROM users WHERE email=?", Long.class, email)).isZero();
        assertThat(database.queryForObject("SELECT count(*) FROM users WHERE phone_number=?", Long.class, "+84" + phone.substring(1))).isZero();
        verifyNoInteractions(delivery);
    }

    long verifiedCompany(Account owner) {
        return new TransactionTemplate(transactions).execute(status -> {
            Long company = database.queryForObject("INSERT INTO companies(name,tax_code,email,registration_certificate_url,verification_status,owner_user_id) VALUES ('Fixture',?,?,'https://example.invalid/certificate.pdf','VERIFIED',?) RETURNING id",
                    Long.class, UUID.randomUUID().toString(), owner.email(), owner.id());
            database.update("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',now())", company, owner.id());
            return company;
        });
    }

    ResultActions send(Account account) throws Exception {
        return http.perform(post(ONBOARDING + "/email/send").header("Authorization", account.token()));
    }

    ResultActions confirm(Account account, String secret) throws Exception {
        return http.perform(post(ONBOARDING + "/email/verify").header("Authorization", account.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("emailSecret", secret))));
    }

    Account enrolledAccount() throws Exception {
        String username = UUID.randomUUID().toString().replace("-", "");
        String email = username + "@example.vn";
        String password = "GuardWorkTest123!";
        http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("firstName", "An", "lastName", "Nguyễn", "username", username,
                        "email", email, "password", password))))
                .andExpect(status().isCreated());
        long id = database.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
        database.update("INSERT INTO employer_profiles(user_id,applicant,full_name) VALUES (?,true,'Nguyễn An')", id);
        MvcResult result = http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("usernameOrEmail", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = json.readTree(result.getResponse().getContentAsString()).path("data");
        return new Account(id, email, "Bearer " + data.path("accessToken").asText());
    }
}
