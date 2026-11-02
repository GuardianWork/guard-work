package com.guardwork.backend.company;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.auth.service.JwtService;
import com.guardwork.backend.onboarding.delivery.DecisionDeliveryWorker;
import com.guardwork.backend.onboarding.delivery.OnboardingDelivery;
import com.guardwork.backend.onboarding.repository.DecisionDeliveryRepository;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;

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

    @BeforeEach void setup() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(delivery.memberEmail(anyString(), anyString())).thenAnswer(call -> {
            emailSecrets.put(call.getArgument(0), call.getArgument(1)); return true;
        });
        when(delivery.decisionEmail(anyString(), anyString(), anyString(), nullable(String.class), any())).thenReturn(true);
    }

    @Test void sharedJourneyPreservesReviewedEvidenceActualStaffIdentityAndDecisionDeliveryRecovery() throws Exception {
        Account owner = account();
        verify(delivery, never()).memberEmail(anyString(), anyString());
        http.perform(get("/api/candidates/profile").header("Authorization", owner.token())).andExpect(status().isOk());
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
        for (String authorized : new String[] {owner.token(), staff}) {
            http.perform(get("/api/company-verification-documents/" + oldDocument).header("Authorization", authorized))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(content().bytes("%PDF-license".getBytes(StandardCharsets.UTF_8)));
        }
        http.perform(get("/api/staff/companies/" + company + "/verification-history").header("Authorization", staff))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        http.perform(put("/api/staff/companies/" + company + "/verify").header("Authorization", staff)
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("status", "VERIFIED", "expectedVersion", 2))))
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
        http.perform(get("/api/candidates/profile").header("Authorization", owner.token())).andExpect(status().isOk());
    }

    JsonNode history(String token) throws Exception {
        return data(http.perform(get("/api/companies/me/verification/history").header("Authorization", token))
                .andExpect(status().isOk()).andReturn());
    }
    Map<String, Object> initial(String tax) {
        return Map.of("name", "Công ty Ánh Dương", "taxCode", tax, "legal", legal("Địa chỉ ban đầu"));
    }
    Map<String, Object> legal(String address) {
        return Map.of("workAddress", address, "submitterPosition", "Giám đốc",
                "representation", "LEGAL_REPRESENTATIVE", "declarationAccepted", true);
    }
    MockMultipartHttpServletRequestBuilder dossier(String method, String path, Map<String, Object> form) throws Exception {
        return multipart(HttpMethod.valueOf(method), path)
                .file(new MockMultipartFile("dossier", "dossier.json", "application/json", json.writeValueAsBytes(form)))
                .file(new MockMultipartFile("file", "giấy-phép.pdf", "application/pdf", "%PDF-license".getBytes(StandardCharsets.UTF_8)));
    }
    Account account() throws Exception {
        String name = "journey_" + UUID.randomUUID().toString().replace("-", "");
        String email = name + "@example.vn";
        long id = data(http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("firstName", "An", "lastName", "Nguyễn", "username", name,
                        "email", email, "password", "GuardworkTest123"))))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        var login = data(http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("usernameOrEmail", name, "password", "GuardworkTest123"))))
                .andExpect(status().isOk()).andReturn());
        return new Account(id, email, "Bearer " + login.path("accessToken").asText());
    }
    void verifyEmail(Account actor) throws Exception {
        http.perform(post("/api/me/email-verification/request").header("Authorization", actor.token())).andExpect(status().isOk());
        http.perform(post("/api/me/email-verification/confirm").header("Authorization", actor.token())
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("emailSecret", emailSecrets.get(actor.email())))))
                .andExpect(status().isOk());
    }
    String body(Object value) throws Exception { return json.writeValueAsString(value); }
    JsonNode data(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()).path("data"); }
    record Account(long id, String email, String token) {}
}
