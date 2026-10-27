package com.guardwork.backend.company;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "employer.onboarding.delivery-worker-enabled=false")
class EmployerOnboardingHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate database;
    @Autowired DecisionDeliveryRepository decisionDeliveries;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JwtService jwt;
    @MockitoBean OnboardingDelivery delivery;
    MockMvc http;
    final Map<String, String> emailSecrets = new ConcurrentHashMap<>();
    static final String PASSWORD = "GuardworkTest123";

    @BeforeEach void setup() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(delivery.memberEmail(anyString(), anyString())).thenAnswer(call -> {
            String email = call.getArgument(0);
            assertThat(database.queryForObject("SELECT count(*) FROM users WHERE email=?", Long.class, email)).isEqualTo(1);
            emailSecrets.put(email, call.getArgument(1));
            return true;
        });
        when(delivery.decisionEmail(anyString(), anyString(), anyString(), nullable(String.class), any()))
                .thenReturn(true);
    }

    record Signup(long id, String email, String phone) {
    }

    @Test
    void fullJourneyPreservesReviewedEvidenceAndActualAdminIdentity() throws Exception {
        Signup signup = begin(phone());
        assertThat(database.queryForObject("SELECT count(*) FROM users WHERE email=?", Long.class, signup.email()))
                .isEqualTo(1);
        http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("usernameOrEmail", signup.email(), "password", PASSWORD))))
                .andExpect(status().isOk());
        String token = confirmEmail(signup);
        http.perform(get("/api/v1/candidates/profile").header("Authorization", token))
                .andExpect(status().isForbidden());
        http.perform(post("/api/companies/verification-request").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("name", "Company"))))
                .andExpect(status().isBadRequest());
        String document = upload(token);
        String tax = UUID.randomUUID().toString();
        http.perform(dossier("POST", "/api/companies/verification-request", initial(tax)).header("Authorization", owner.token()))
                .andExpect(status().isForbidden());
        verifyEmail(owner);
        var submitted = data(http.perform(dossier("POST", "/api/companies/verification-request", initial(tax))
                .header("Authorization", owner.token())).andExpect(status().isCreated()).andReturn());
        long company = submitted.path("id").asLong();
        assertThat(submitted.path("email").asText()).isEqualTo(owner.email());
        assertThat(submitted.path("processingStatus").asText()).isEqualTo("PENDING");
        var draft = data(http.perform(post("/api/companies/me/jobs").header("Authorization", owner.token())
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("content", Map.of("title", "Nhân viên bảo vệ")))))
                .andExpect(status().isCreated()).andReturn());
        long job = draft.path("id").asLong();
        http.perform(get("/api/companies/me/jobs/" + job + "/draft/preview").header("Authorization", owner.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.submissionEligible").value(false))
                .andExpect(jsonPath("$.data.validationIssues[?(@.code == 'COMPANY_NOT_VERIFIED')]").isNotEmpty());
        Account staffAccount = account();
        database.update("UPDATE users SET role='STAFF' WHERE id=?", staffAccount.id());
        String staff = "Bearer " + jwt.generateAccessToken(staffAccount.id(), "staff", staffAccount.email(), "STAFF");
        http.perform(put("/api/staff/companies/" + company + "/verify").header("Authorization", owner.token())
                .header("X-Admin-Id", staffAccount.id()).header("X-Admin-Role", "STAFF")
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("status", "VERIFIED", "expectedVersion", 0))))
                .andExpect(status().isForbidden());
        http.perform(put("/api/staff/companies/" + company + "/verify").header("Authorization", staff)
                .header("X-Admin-Id", owner.id()).header("X-Admin-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("status", "REJECTED", "expectedVersion", 0,
                        "rejectionReason", "Giấy phép chưa rõ thông tin"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewOutcome").value("REJECTED"));
        var oldAttempt = history(owner.token()).get(0);
        UUID rejectedAttempt = UUID.fromString(oldAttempt.path("attemptId").asText());
        assertThat(oldAttempt.path("reviewerId").asLong()).isEqualTo(staffAccount.id());
        assertThat(oldAttempt.path("declarationText").asText()).contains("Tôi cam kết");
        assertThat(decisionDeliveries.findById(rejectedAttempt).orElseThrow().getRecipientEmail()).isEqualTo(owner.email());
        verify(delivery, never()).decisionEmail(anyString(), anyString(), anyString(), nullable(String.class), any());
        var worker = new DecisionDeliveryWorker(decisionDeliveries, delivery, transactions);
        when(delivery.decisionEmail(anyString(), anyString(), anyString(), nullable(String.class), any())).thenReturn(false);
        worker.deliverDue();
        assertThat(decisionDeliveries.findById(rejectedAttempt).orElseThrow().getAttempts()).isEqualTo(1);
        assertThat(decisionDeliveries.findById(rejectedAttempt).orElseThrow().getDeliveredAt()).isNull();
        http.perform(get("/api/companies/me/verification").header("Authorization", owner.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.verificationStatus").value("REJECTED"));
        database.update("UPDATE company_decision_deliveries SET next_attempt_at=now()-interval '1 second' WHERE id=?", rejectedAttempt);
        when(delivery.decisionEmail(anyString(), anyString(), anyString(), nullable(String.class), any())).thenReturn(true);
        worker.deliverDue();
        assertThat(decisionDeliveries.findById(rejectedAttempt).orElseThrow().getDeliveredAt()).isNotNull();
        verify(delivery, times(2)).decisionEmail(eq(owner.email()), eq("Công ty Ánh Dương"), eq("REJECTED"),
                eq("Giấy phép chưa rõ thông tin"), eq(rejectedAttempt));
        String oldDocument = oldAttempt.path("documentId").asText();
        http.perform(dossier("PUT", "/api/companies/me/verification", Map.of("name", "Công ty Ánh Dương mới",
                "expectedVersion", 1, "legal", legal("Địa chỉ cập nhật"))).header("Authorization", owner.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.companyId").value(company))
                .andExpect(jsonPath("$.data.taxCode").value(tax));
        var correctedHistory = history(owner.token());
        assertThat(correctedHistory).hasSize(2);
        assertThat(correctedHistory.get(0)).isEqualTo(oldAttempt);
        assertThat(correctedHistory.get(1).path("documentId").asText()).isNotEqualTo(oldDocument);
        assertThatThrownBy(() -> database.update("UPDATE company_verification_attempts SET work_address='changed' WHERE id=?", rejectedAttempt))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        http.perform(get("/api/company-verification-documents/" + oldDocument).header("Authorization", account().token()))
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
        String token = confirmEmail(signup);
        http.perform(get("/api/me/employer-onboarding").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailVerified").value(true))
                .andExpect(jsonPath("$.data.phoneVerified").doesNotExist())
                .andExpect(jsonPath("$.data.stage").value("COMPANY_ACTION_REQUIRED"));
        http.perform(get("/api/me/employer-onboarding").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("COMPANY_ACTION_REQUIRED"));
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
        var approvedHistory = history(owner.token());
        assertThat(approvedHistory.get(0)).isEqualTo(oldAttempt);
        assertThat(approvedHistory.get(1).path("outcome").asText()).isEqualTo("VERIFIED");
        UUID approvedAttempt = UUID.fromString(approvedHistory.get(1).path("attemptId").asText());
        worker.deliverDue();
        assertThat(decisionDeliveries.findById(approvedAttempt).orElseThrow().getDeliveredAt()).isNotNull();
        verify(delivery).decisionEmail(eq(owner.email()), eq("Công ty Ánh Dương mới"), eq("VERIFIED"), isNull(), eq(approvedAttempt));
        http.perform(get("/api/companies/me/jobs/" + job + "/draft/preview").header("Authorization", owner.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.validationIssues[?(@.code == 'COMPANY_NOT_VERIFIED')]").isEmpty());
        http.perform(get("/api/v1/candidates/profile").header("Authorization", owner.token())).andExpect(status().isOk());
    }

    @Test
    void unverifiedAccountsReserveTheirIdentifiersWithoutResendingMail() throws Exception {
        Signup first = begin(phone());
        String unusedEmail = UUID.randomUUID() + "@example.vn";
        registration(first.email().toUpperCase(Locale.ROOT), phone()).andExpect(status().isConflict());
        registration(unusedEmail, "+84" + first.phone().substring(1)).andExpect(status().isConflict());
        assertThat(database.queryForObject("SELECT count(*) FROM users WHERE email=?", Long.class, first.email())).isEqualTo(1);
        assertThat(database.queryForObject("SELECT count(*) FROM users WHERE email=?", Long.class, unusedEmail)).isZero();
        verify(delivery, times(1)).memberEmail(eq(first.email()), anyString());
    }

    @Test
    void retiredPhoneEndpointsRequireAuthenticationAndHaveNoSideEffects() throws Exception {
        String candidate = ordinaryAccount(false);
        long candidateId = data(http.perform(get("/api/auth/me").header("Authorization", candidate))
                .andExpect(status().isOk()).andReturn()).path("id").asLong();
        Signup signup = begin(phone());
        String employer = confirmEmail(signup);
        clearInvocations(delivery);
        for (String action : List.of("send", "verify")) {
            String path = "/api/me/employer-onboarding/phone/" + action;
            http.perform(post(path)).andExpect(status().isUnauthorized());
            for (String token : List.of(candidate, employer)) {
                http.perform(post(path).header("Authorization", token)).andExpect(status().isGone());
                http.perform(post(path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{malformed")).andExpect(status().isGone());
            }
        }
        verifyNoInteractions(delivery);
        assertThat(database.queryForObject("SELECT count(*) FROM employer_profiles WHERE user_id=?", Long.class, candidateId)).isZero();
        assertThat(database.queryForObject("SELECT phone FROM employer_profiles p JOIN users u ON u.id=p.user_id WHERE u.email=?", String.class, signup.email())).isNull();
        assertThat(database.queryForObject("SELECT phone_verified_at FROM employer_profiles p JOIN users u ON u.id=p.user_id WHERE u.email=?", java.sql.Timestamp.class, signup.email())).isNull();
    }

    @Test
    void ordinaryCandidatesRemainUsableAndOldLinksCannotPromoteOrRevealRecords() throws Exception {
        String candidate = ordinaryAccount(false);
        http.perform(get("/api/v1/candidates/profile").header("Authorization", candidate)).andExpect(status().isOk());
        Signup signup = begin(phone());
        UUID retained = UUID.randomUUID();
        String retainedSecret = com.guardwork.backend.onboarding.service.OnboardingSecrets.hash("retained-secret");
        database.update("INSERT INTO employer_registrations(id,continuation_hash,email,password_hash,full_name,gender,phone,company_name,work_address,email_secret_hash,email_expires_at,email_sent_at,created_at) VALUES (?,?,?,'retained-password-hash','Legacy attempt','OTHER',?,'Legacy company','Legacy address',?,now()+interval '1 day',now(),now())",
                retained, com.guardwork.backend.onboarding.service.OnboardingSecrets.hash("retained-continuation"), signup.email(), signup.phone(), retainedSecret);
        for (String action : List.of("resend", "verify")) {
            for (String id : List.of(retained.toString(), UUID.randomUUID().toString(), "invalid-id")) {
                http.perform(post("/api/employer-registrations/" + id + "/email/" + action))
                        .andExpect(status().isGone()).andExpect(jsonPath("$.status").value(410));
                http.perform(post("/api/employer-registrations/" + id + "/email/" + action)
                        .contentType(MediaType.APPLICATION_JSON).content("{malformed"))
                        .andExpect(status().isGone());
            }
        }
        assertThat(database.queryForObject("SELECT promoted_user_id FROM employer_registrations WHERE id=?", Long.class, retained)).isNull();
        assertThat(database.queryForObject("SELECT email_secret_hash FROM employer_registrations WHERE id=?", String.class, retained)).isEqualTo(retainedSecret);
        assertThat(database.queryForObject("SELECT full_name FROM employer_profiles WHERE user_id=?", String.class, signup.id())).isEqualTo("Nguyễn Ánh Dương");
        assertThat(database.queryForObject("SELECT email_secret_hash FROM employer_profiles WHERE user_id=?", String.class, signup.id()))
                .isNotEqualTo(emailSecrets.get(signup.email()));
    }

    @Test
    void concurrentRegistrationsWithEmailAliasesCreateOneAccount() throws Exception {
        String email = UUID.randomUUID() + "@example.vn";
        concurrentClaims(email, phone(), " " + email.toUpperCase(Locale.ROOT) + " ", phone());
        assertThat(database.queryForObject("SELECT count(*) FROM users WHERE lower(btrim(email))=?", Long.class, email)).isEqualTo(1);
        verify(delivery, times(1)).memberEmail(eq(email), anyString());
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
    void failedEmailSubmissionKeepsTheOfficialAccountAndAllowsRestrictedSignIn() throws Exception {
        doThrow(new IllegalStateException("provider unavailable")).when(delivery).memberEmail(anyString(), anyString());
        String email = UUID.randomUUID() + "@example.vn";
        registration(email, phone()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.deliveryAccepted").value(false))
                .andExpect(jsonPath("$.data.emailVerified").value(false))
                .andExpect(jsonPath("$.data.stage").value("EMAIL_REQUIRED"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.continuationToken").doesNotExist());
        String token = login(email);
        http.perform(get("/api/me/employer-onboarding").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailVerified").value(false));
        http.perform(post("/api/me/employer-onboarding/email/send").header("Authorization", token))
                .andExpect(status().isTooManyRequests());
        database.update("UPDATE employer_profiles SET email_sent_at=now()-interval '61 seconds' WHERE user_id=(SELECT id FROM users WHERE email=?)", email);
        doReturn(true).when(delivery).memberEmail(anyString(), anyString());
        http.perform(post("/api/me/employer-onboarding/email/send").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deliveryAccepted").value(true));
    }

    @Test
    void candidatePhoneClaimsBlockEmployerRegistrationAndEmployerClaimsBlockCandidateUpdates() throws Exception {
        String number = phone();
        String candidate = ordinaryAccount(false);
        http.perform(put("/api/v1/candidates/profile").header("Authorization", candidate)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("fullName", "Nguyễn An", "phoneNumber", number))))
                .andExpect(status().isOk());
        registration(UUID.randomUUID() + "@example.vn", "+84" + number.substring(1)).andExpect(status().isConflict());
        Signup employer = begin(phone());
        http.perform(put("/api/v1/candidates/profile").header("Authorization", candidate)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("fullName", "Must roll back", "phoneNumber", employer.phone()))))
                .andExpect(status().isConflict());
        http.perform(get("/api/v1/candidates/profile").header("Authorization", candidate))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fullName").value("Nguyễn An"));
    }

    @Test
    void concurrentRegistrationsWithPhoneAliasesCreateOneAccountAndProfile() throws Exception {
        String number = phone();
        String first = UUID.randomUUID() + "@example.vn";
        String second = UUID.randomUUID() + "@example.vn";
        concurrentClaims(first, number, second, "+84" + number.substring(1));
        assertThat(database.queryForObject("SELECT count(*) FROM users WHERE phone_number=?", Long.class,
                "+84" + number.substring(1))).isEqualTo(1);
        assertThat(database.queryForObject("SELECT count(*) FROM employer_profiles p JOIN users u ON u.id=p.user_id WHERE u.email IN (?,?)", Long.class,
                first, second)).isEqualTo(1);
    }

    void concurrentClaims(String firstEmail, String firstPhone, String secondEmail, String secondPhone) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (var claim : List.of(Map.entry(firstEmail, firstPhone), Map.entry(secondEmail, secondPhone))) {
                results.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return registration(claim.getKey(), claim.getValue()).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            assertThat(List.of(results.get(0).get(30, TimeUnit.SECONDS), results.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
    }

    @Test
    void declarationAndPrivateDocumentAreRequiredAndDuplicateMstLeavesNoExtraCompany() throws Exception {
        Signup first = begin(phone());
        String token = confirmEmail(first);
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
        String another = confirmEmail(second);
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
        var response = data(registration(email, phone).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.emailSecret").doesNotExist())
                .andExpect(jsonPath("$.data.registrationId").doesNotExist())
                .andExpect(jsonPath("$.data.continuationToken").doesNotExist()).andReturn());
        return new Signup(response.path("userId").asLong(), email, phone);
    }

    ResultActions registration(String email, String phone) throws Exception {
        return http.perform(post("/api/employer-registrations").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("fullName", "Nguyễn Ánh Dương", "phone", phone, "companyName", "Công ty Ánh Dương",
                        "gender", "UNDISCLOSED", "workAddress", "Hồ Chí Minh", "email", email,
                        "password", PASSWORD, "passwordConfirmation", PASSWORD))));
    }

    String confirmEmail(Signup s) throws Exception {
        String token = login(s.email());
        http.perform(post("/api/me/employer-onboarding/email/verify").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("emailSecret", emailSecrets.get(s.email())))))
                .andExpect(status().isOk());
        return token;
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
    String body(Object value) throws Exception { return json.writeValueAsString(value); }
    JsonNode data(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()).path("data"); }
    record Account(long id, String email, String token) {}
}
