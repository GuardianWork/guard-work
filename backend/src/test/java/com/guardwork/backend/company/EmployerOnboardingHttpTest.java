package com.guardwork.backend.company;

import com.fasterxml.jackson.databind.*;
import com.guardwork.backend.onboarding.delivery.OnboardingDelivery;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = { "ONBOARDING_OTP_SECRET=test-only-otp-pepper",
        "employer.onboarding.delivery-worker-enabled=false" })
class EmployerOnboardingHttpTest {
    @Autowired
    WebApplicationContext context;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate database;
    @Autowired
    com.guardwork.backend.onboarding.repository.DecisionDeliveryRepository decisionDeliveries;
    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean
    OnboardingDelivery delivery;
    MockMvc http;
    final Map<UUID, String> emailSecrets = new ConcurrentHashMap<>();
    final Map<String, String> phoneCodes = new ConcurrentHashMap<>();
    static final String PASSWORD = "GuardworkTest123";

    @BeforeEach
    void setup() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(delivery.registrationEmail(anyString(), any(), anyString())).thenAnswer(call -> {
            emailSecrets.put(call.getArgument(1), call.getArgument(2));
            return true;
        });
        when(delivery.sms(anyString(), anyString())).thenAnswer(call -> {
            phoneCodes.put(call.getArgument(0), call.getArgument(1));
            return true;
        });
        when(delivery.decisionEmail(anyString(), anyString(), anyString(), nullable(String.class), any()))
                .thenReturn(true);
    }

    record Signup(UUID id, String continuation, String email, String phone) {
    }

    @Test
    void fullJourneyPreservesReviewedEvidenceAndActualAdminIdentity() throws Exception {
        Signup signup = begin(phone());
        assertThat(database.queryForObject("SELECT count(*) FROM users WHERE email=?", Long.class, signup.email()))
                .isZero();
        http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("usernameOrEmail", signup.email(), "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
        String token = promote(signup);
        http.perform(get("/api/v1/candidates/profile").header("Authorization", token))
                .andExpect(status().isForbidden());
        http.perform(post("/api/companies/verification-request").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("name", "Company"))))
                .andExpect(status().isForbidden());
        verifyPhone(signup, token);
        String document = upload(token);
        String tax = UUID.randomUUID().toString();
        var legal = legal(document, "Địa chỉ ban đầu");
        var submitted = data(http.perform(post("/api/companies/verification-request").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("name", "Công ty Ánh Dương", "taxCode", tax, "legal", legal))))
                .andExpect(status().isCreated()).andReturn());
        long company = submitted.path("id").asLong();
        assertThat(submitted.path("email").asText()).isEqualTo(signup.email());
        assertThat(submitted.path("processingStatus").asText()).isEqualTo("PENDING");
        String admin = ordinaryAccount(true);
        long ownerId = database.queryForObject("SELECT id FROM users WHERE email=?", Long.class, signup.email());
        http.perform(put("/api/staff/companies/" + company + "/verify").header("Authorization", admin)
                .header("X-Admin-Id", ownerId).header("X-Admin-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("status", "REJECTED", "expectedVersion", 0,
                        "rejectionReason", "Giấy phép chưa rõ thông tin"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.processingStatus").value("CHECKED"))
                .andExpect(jsonPath("$.data.reviewOutcome").value("REJECTED"));
        long reviewer = database.queryForObject("SELECT verified_by FROM companies WHERE id=?", Long.class, company);
        assertThat(reviewer).isNotEqualTo(ownerId);
        assertThat(database.queryForObject(
                "SELECT count(*) FROM company_decision_deliveries d JOIN company_verification_attempts a ON a.id=d.id WHERE a.company_id=?",
                Long.class, company)).isEqualTo(1);
        // A decision records delivery intent in its transaction; the provider is not
        // invoked there.
        verify(delivery, never()).decisionEmail(anyString(), anyString(), anyString(), nullable(String.class), any());
        var worker = new com.guardwork.backend.onboarding.delivery.DecisionDeliveryWorker(decisionDeliveries, delivery,
                transactions);
        when(delivery.decisionEmail(anyString(), anyString(), anyString(), nullable(String.class), any()))
                .thenReturn(false);
        worker.deliverDue();
        assertThat(
                database.queryForObject("SELECT verification_status FROM companies WHERE id=?", String.class, company))
                .isEqualTo("REJECTED");
        assertThat(database.queryForObject(
                "SELECT d.attempts FROM company_decision_deliveries d JOIN company_verification_attempts a ON a.id=d.id WHERE a.company_id=?",
                Integer.class, company)).isEqualTo(1);
        database.update(
                "UPDATE company_decision_deliveries SET next_attempt_at=now()-interval '1 second' WHERE id IN (SELECT id FROM company_verification_attempts WHERE company_id=?)",
                company);
        when(delivery.decisionEmail(anyString(), anyString(), anyString(), nullable(String.class), any()))
                .thenReturn(true);
        worker.deliverDue();
        assertThat(database.queryForObject(
                "SELECT count(*) FROM company_decision_deliveries d JOIN company_verification_attempts a ON a.id=d.id WHERE a.company_id=? AND d.delivered_at IS NOT NULL",
                Long.class, company)).isEqualTo(1);
        http.perform(put("/api/companies/me/verification").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("name", "Công ty Ánh Dương mới", "expectedVersion", 1,
                        "legal", legal(document, "Địa chỉ cập nhật")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.verificationStatus").value("PENDING"));
        var history = data(http.perform(get("/api/companies/me/verification/history").header("Authorization", token))
                .andExpect(status().isOk()).andReturn());
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).path("workAddress").asText()).isEqualTo("Địa chỉ ban đầu");
        assertThat(history.get(0).path("outcome").asText()).isEqualTo("REJECTED");
        assertThat(history.get(0).path("declarationText").asText()).contains("Tôi cam kết");
        assertThat(history.get(1).path("outcome").isNull()).isTrue();
        assertThatThrownBy(() -> database
                .update("UPDATE company_verification_attempts SET work_address='changed' WHERE company_id=?", company))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        http.perform(
                get("/api/company-verification-documents/" + document).header("Authorization", ordinaryAccount(false)))
                .andExpect(status().isNotFound());
        http.perform(get("/api/company-verification-documents/" + document).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        http.perform(get("/api/staff/companies/" + company + "/verification-history")
                .header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].outcome").value("REJECTED"));
        http.perform(get("/api/company-verification-documents/" + document).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        http.perform(get("/api/companies/me/invitations").header("Authorization", token))
                .andExpect(status().isForbidden());
        http.perform(put("/api/staff/companies/" + company + "/verify").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("status", "VERIFIED", "expectedVersion", 2))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.processingStatus").value("CHECKED"))
                .andExpect(jsonPath("$.data.reviewOutcome").value("VERIFIED"));
        http.perform(get("/api/me/employer-onboarding").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("VERIFIED"));
        http.perform(get("/api/companies/me/invitations").header("Authorization", token)).andExpect(status().isOk());
        var approvedHistory = data(
                http.perform(get("/api/companies/me/verification/history").header("Authorization", token))
                        .andExpect(status().isOk()).andReturn());
        assertThat(approvedHistory.size()).isEqualTo(2);
        assertThat(approvedHistory.get(0).path("outcome").asText()).isEqualTo("REJECTED");
        assertThat(approvedHistory.get(1).path("outcome").asText()).isEqualTo("VERIFIED");
    }

    @Test
    void directApprovalUnlocksEmployerAccessAndQueuesApprovalEmail() throws Exception {
        Signup signup = begin(phone());
        String token = promote(signup);
        http.perform(get("/api/me/employer-onboarding").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailVerified").value(true))
                .andExpect(jsonPath("$.data.phoneVerified").value(false))
                .andExpect(jsonPath("$.data.stage").value("PHONE_REQUIRED"));
        verifyPhone(signup, token);
        http.perform(get("/api/me/employer-onboarding").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("LEGAL_REQUIRED"));
        String document = upload(token);
        var company = data(http.perform(post("/api/companies/verification-request").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("name", "Công ty Thành Công", "taxCode", UUID.randomUUID().toString(),
                        "legal", legal(document, "Hồ Chí Minh")))))
                .andExpect(status().isCreated()).andReturn());
        long id = company.path("id").asLong();
        http.perform(get("/api/companies/me/invitations").header("Authorization", token))
                .andExpect(status().isForbidden());
        http.perform(put("/api/staff/companies/" + id + "/verify").header("Authorization", token)
                .header("X-Staff-Role", "STAFF").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("status", "VERIFIED", "expectedVersion", 0)))).andExpect(status().isForbidden());
        http.perform(put("/api/staff/companies/" + id + "/verify").header("Authorization", ordinaryAccount(true))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("status", "VERIFIED", "expectedVersion", 0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewOutcome").value("VERIFIED"));
        http.perform(get("/api/me/employer-onboarding").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("VERIFIED"))
                .andExpect(jsonPath("$.data.companyRole").value("OWNER"));
        http.perform(get("/api/companies/me/invitations").header("Authorization", token)).andExpect(status().isOk());
        var history = data(http.perform(get("/api/companies/me/verification/history").header("Authorization", token))
                .andExpect(status().isOk()).andReturn());
        UUID decision = UUID.fromString(history.get(0).path("attemptId").asText());
        new com.guardwork.backend.onboarding.delivery.DecisionDeliveryWorker(decisionDeliveries, delivery, transactions)
                .deliverDue();
        verify(delivery).decisionEmail(eq(signup.email()), eq("Công ty Thành Công"), eq("VERIFIED"), isNull(),
                eq(decision));
    }

    @Test
    void unverifiedClaimsDoNotReserveEmailAndOnlyOneAttemptPromotes() throws Exception {
        Signup first = begin(phone());
        Signup second = begin(first.email(), phone());
        http.perform(post("/api/employer-registrations/" + first.id() + "/email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("continuationToken", first.continuation(), "emailSecret",
                        emailSecrets.get(first.id()), "password", "wrong"))))
                .andExpect(status().isBadRequest());
        promote(second);
        http.perform(post("/api/employer-registrations/" + first.id() + "/email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("continuationToken", first.continuation(), "emailSecret",
                        emailSecrets.get(first.id()), "password", PASSWORD))))
                .andExpect(status().isConflict());
        assertThat(database.queryForObject("SELECT count(*) FROM users WHERE email=?", Long.class, first.email()))
                .isEqualTo(1);
    }

    @Test
    void exhaustedOtpAttemptsCommitAndUndeliveredProofCannotVerify() throws Exception {
        Signup signup = begin(phone());
        String token = promote(signup);
        http.perform(post("/api/me/employer-onboarding/phone/send").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("phone", signup.phone())))).andExpect(status().isOk());
        String correct = phoneCodes.get("+84" + signup.phone().substring(1));
        for (int i = 0; i < 5; i++)
            verifyCode(token, "999999".equals(correct) ? "000000" : "999999", 400);
        verifyCode(token, correct, 400);
        long id = database.queryForObject("SELECT id FROM users WHERE email=?", Long.class, signup.email());
        assertThat(database.queryForObject("SELECT phone_failures FROM employer_profiles WHERE user_id=?",
                Integer.class, id)).isEqualTo(5);
        http.perform(post("/api/me/employer-onboarding/phone/send").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("phone", signup.phone())))).andExpect(status().isTooManyRequests());
        Signup failed = begin(phone());
        String other = promote(failed);
        when(delivery.sms(anyString(), anyString())).thenReturn(false);
        http.perform(post("/api/me/employer-onboarding/phone/send").header("Authorization", other)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("phone", failed.phone())))).andExpect(status().isServiceUnavailable());
        verifyCode(other, "123456", 400);
    }

    @Test
    void ordinaryCandidatesRemainUsableAndPublicSecretsNeverAppearInResponses() throws Exception {
        String candidate = ordinaryAccount(false);
        http.perform(get("/api/v1/candidates/profile").header("Authorization", candidate)).andExpect(status().isOk());
        Signup signup = begin(phone());
        http.perform(post("/api/employer-registrations/" + signup.id() + "/email/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("continuationToken", "another-attempt")))).andExpect(status().isBadRequest());
        http.perform(post("/api/employer-registrations/" + signup.id() + "/email/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("continuationToken", signup.continuation()))))
                .andExpect(status().isTooManyRequests());
        assertThat(database.queryForObject("SELECT email_secret_hash FROM employer_registrations WHERE id=?",
                String.class, signup.id()))
                .isNotEqualTo(emailSecrets.get(signup.id()));
    }

    @Test
    void concurrentEmailPromotionCreatesOnlyOneOfficialAccount() throws Exception {
        Signup a = begin(phone());
        Signup b = begin(a.email(), phone());
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (Signup s : List.of(a, b))
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return http
                            .perform(post("/api/employer-registrations/" + s.id() + "/email/verify")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body(Map.of("continuationToken", s.continuation(), "emailSecret",
                                            emailSecrets.get(s.id()), "password", PASSWORD))))
                            .andReturn().getResponse().getStatus();
                }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(results.get(0).get(30, TimeUnit.SECONDS), results.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test
    void multibytePasswordsBeyondBcryptLimitAreValidationErrors() throws Exception {
        String password = "Aa1" + "ấ".repeat(30);
        http.perform(post("/api/employer-registrations").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("fullName", "Nguyễn An", "phone", phone(), "companyName", "Company", "gender",
                        "OTHER",
                        "workAddress", "Hà Nội", "email", UUID.randomUUID() + "@example.vn", "password", password,
                        "passwordConfirmation", password))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resendReplacesProofAndExpiredEmailCannotPromote() throws Exception {
        Signup signup = begin(phone());
        String first = emailSecrets.get(signup.id());
        database.update("UPDATE employer_registrations SET email_sent_at=now()-interval '61 seconds' WHERE id=?",
                signup.id());
        http.perform(post("/api/employer-registrations/" + signup.id() + "/email/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("continuationToken", signup.continuation())))).andExpect(status().isOk());
        http.perform(post("/api/employer-registrations/" + signup.id() + "/email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("continuationToken", signup.continuation(), "emailSecret", first, "password",
                        PASSWORD))))
                .andExpect(status().isBadRequest());
        database.update("UPDATE employer_registrations SET email_expires_at=now()-interval '1 second' WHERE id=?",
                signup.id());
        http.perform(post("/api/employer-registrations/" + signup.id() + "/email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("continuationToken", signup.continuation(), "emailSecret",
                        emailSecrets.get(signup.id()), "password", PASSWORD))))
                .andExpect(status().isBadRequest());
        assertThat(database.queryForObject("SELECT count(*) FROM users WHERE email=?", Long.class, signup.email()))
                .isZero();
    }

    @Test
    void competingNormalizedPhoneClaimsHaveOneVerifiedOwner() throws Exception {
        String number = phone();
        Signup a = begin(number);
        Signup b = begin(number);
        String first = promote(a);
        String second = promote(b);
        http.perform(post("/api/me/employer-onboarding/phone/send").header("Authorization", first)
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("phone", number))))
                .andExpect(status().isOk());
        String firstCode = phoneCodes.get("+84" + number.substring(1));
        http.perform(post("/api/me/employer-onboarding/phone/send").header("Authorization", second)
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("phone", "+84" + number.substring(1)))))
                .andExpect(status().isOk());
        String secondCode = phoneCodes.get("+84" + number.substring(1));
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (var request : List.of(Map.entry(first, firstCode), Map.entry(second, secondCode)))
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return http.perform(post("/api/me/employer-onboarding/phone/verify")
                            .header("Authorization", request.getKey())
                            .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("code", request.getValue()))))
                            .andReturn().getResponse().getStatus();
                }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(results.get(0).get(30, TimeUnit.SECONDS), results.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(database.queryForObject(
                "SELECT count(*) FROM employer_profiles WHERE phone=? AND phone_verified_at IS NOT NULL",
                Long.class, "+84" + number.substring(1))).isEqualTo(1);
    }

    @Test
    void declarationAndPrivateDocumentAreRequiredAndDuplicateMstLeavesNoExtraCompany() throws Exception {
        Signup first = begin(phone());
        String token = promote(first);
        verifyPhone(first, token);
        http.perform(multipart("/api/me/employer-onboarding/documents")
                .file(new MockMultipartFile("file", "fake.pdf", "application/pdf",
                        "<html>not a PDF</html>".getBytes(StandardCharsets.US_ASCII)))
                .header("Authorization", token)).andExpect(status().isBadRequest());
        String doc = upload(token);
        String tax = UUID.randomUUID().toString();
        Map<String, Object> missing = new HashMap<>(legal(doc, "Hà Nội"));
        missing.put("declarationAccepted", false);
        http.perform(post("/api/companies/verification-request").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("name", "Company", "taxCode", tax, "legal", missing))))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/companies/verification-request").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("name", "Company", "taxCode", tax, "legal", legal(doc, "Hà Nội")))))
                .andExpect(status().isCreated());
        Signup second = begin(phone());
        String another = promote(second);
        verifyPhone(second, another);
        String otherDoc = upload(another);
        http.perform(post("/api/companies/verification-request").header("Authorization", another)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("name", "Duplicate", "taxCode", tax, "legal", legal(otherDoc, "Hà Nội")))))
                .andExpect(status().isConflict());
        assertThat(database.queryForObject("SELECT count(*) FROM companies WHERE tax_code=?", Long.class, tax))
                .isEqualTo(1);
        assertThat(database.queryForObject(
                "SELECT count(*) FROM company_memberships m JOIN users u ON u.id=m.user_id WHERE u.email=?", Long.class,
                second.email())).isZero();
    }

    Signup begin(String phone) throws Exception {
        return begin(UUID.randomUUID() + "@example.vn", phone);
    }

    Signup begin(String email, String phone) throws Exception {
        var response = data(http.perform(post("/api/employer-registrations").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("fullName", "Nguyễn Ánh Dương", "phone", phone, "companyName", "Công ty Ánh Dương",
                        "gender", "UNDISCLOSED", "workAddress", "Hồ Chí Minh", "email", email,
                        "password", PASSWORD, "passwordConfirmation", PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.emailSecret").doesNotExist()).andReturn());
        return new Signup(UUID.fromString(response.path("registrationId").asText()),
                response.path("continuationToken").asText(), email, phone);
    }

    String promote(Signup s) throws Exception {
        http.perform(
                post("/api/employer-registrations/" + s.id() + "/email/verify").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("continuationToken", s.continuation(), "emailSecret",
                                emailSecrets.get(s.id()), "password", PASSWORD))))
                .andExpect(status().isOk());
        return login(s.email());
    }

    void verifyPhone(Signup s, String token) throws Exception {
        http.perform(post("/api/me/employer-onboarding/phone/send").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("phone", s.phone())))).andExpect(status().isOk());
        verifyCode(token, phoneCodes.get("+84" + s.phone().substring(1)), 200);
    }

    void verifyCode(String token, String code, int expected) throws Exception {
        http.perform(post("/api/me/employer-onboarding/phone/verify").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("code", code)))).andExpect(status().is(expected));
    }

    String upload(String token) throws Exception {
        return data(http.perform(multipart("/api/me/employer-onboarding/documents")
                .file(new MockMultipartFile("file", "license.pdf", "application/pdf",
                        "%PDF-1.4\nretained evidence".getBytes(StandardCharsets.US_ASCII)))
                .header("Authorization", token)).andExpect(status().isOk()).andReturn()).path("documentId").asText();
    }

    Map<String, Object> legal(String doc, String address) {
        return Map.of("workAddress", address, "submitterPosition", "Giám đốc", "representation",
                "AUTHORIZED_REPRESENTATIVE",
                "declarationAccepted", true, "documentId", doc);
    }

    String ordinaryAccount(boolean admin) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        String email = id + "@candidate.vn";
        http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body(Map.of(
                "firstName", "Nguyễn", "lastName", "An", "username", id, "email", email, "password", PASSWORD))))
                .andExpect(status().isCreated());
        if (admin)
            database.update("UPDATE users SET role='STAFF' WHERE email=?", email);
        return login(email);
    }

    String login(String email) throws Exception {
        return "Bearer " + data(http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("usernameOrEmail", email, "password", PASSWORD)))).andExpect(status().isOk())
                .andReturn()).path("accessToken").asText();
    }

    String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    static String phone() {
        return "09%08d".formatted(Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000_000));
    }
}
