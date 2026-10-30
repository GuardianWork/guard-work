package com.guardwork.backend.company;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.onboarding.delivery.OnboardingDelivery;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class CompleteCompanyDossierHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate database;
    @MockitoBean OnboardingDelivery delivery;
    MockMvc http;
    final Map<String, String> proofs = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach void setup() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(delivery.memberEmail(anyString(), anyString())).thenAnswer(call -> {
            proofs.put(call.getArgument(0), call.getArgument(1)); return true;
        });
    }

    @Test void completeSubmissionCreatesPendingOwnerAndPrivateImmutableEvidenceTogether() throws Exception {
        String owner = account(true);
        var response = data(http.perform(dossier("POST", "/api/companies/verification-request", form(UUID.randomUUID().toString()))
                .header("Authorization", owner)).andExpect(status().isCreated()).andReturn());
        long id = response.path("id").asLong();
        assertThat(response.path("verificationStatus").asText()).isEqualTo("PENDING");
        http.perform(get("/api/auth/me").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeMembership.companyId").value(id))
                .andExpect(jsonPath("$.data.activeMembership.companyRole").value("OWNER"));
        var history = data(http.perform(get("/api/companies/me/verification/history").header("Authorization", owner))
                .andExpect(status().isOk()).andReturn());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("workAddress").asText()).isEqualTo("Địa chỉ công ty");
        assertThat(history.get(0).path("declarationText").asText()).contains("Tôi cam kết");
        String document = history.get(0).path("documentId").asText();
        http.perform(get("/api/company-verification-documents/" + document).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes("%PDF-test".getBytes(StandardCharsets.UTF_8)));
        http.perform(get("/api/company-verification-documents/" + document).header("Authorization", account(true)))
                .andExpect(status().isNotFound());
    }

    @Test void suspendedCompanyOwnerCanReadStatusAndEvidenceButCannotCorrect() throws Exception {
        String owner = account(true);
        long id = data(http.perform(dossier("POST", "/api/companies/verification-request", form(UUID.randomUUID().toString()))
                .header("Authorization", owner)).andExpect(status().isCreated()).andReturn()).path("id").asLong();
        String document = data(http.perform(get("/api/companies/me/verification/history").header("Authorization", owner))
                .andExpect(status().isOk()).andReturn()).get(0).path("documentId").asText();
        database.update("UPDATE companies SET is_banned=TRUE WHERE id=?", id);
        http.perform(get("/api/companies/me").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companySuspended").value(true));
        http.perform(get("/api/companies/me/verification").header("Authorization", owner)).andExpect(status().isOk());
        http.perform(get("/api/companies/me/verification/history").header("Authorization", owner)).andExpect(status().isOk());
        http.perform(get("/api/company-verification-documents/" + document).header("Authorization", owner)).andExpect(status().isOk());
        var correction = new java.util.HashMap<String,Object>(form("ignored"));
        correction.put("expectedVersion", 0);
        http.perform(dossier("PUT", "/api/companies/me/verification", correction).header("Authorization", owner))
                .andExpect(status().isNotFound());
    }

    @Test void duplicateFilesAreRejectedWithoutCreatingACompany() throws Exception {
        String owner = account(true);
        http.perform(dossier("POST", "/api/companies/verification-request", form(UUID.randomUUID().toString()))
                .file(new MockMultipartFile("file", "second.pdf", "application/pdf", "%PDF-second".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", owner)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("40001 INVALID_INPUT: Exactly one registration document is required"));
        http.perform(get("/api/auth/me").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeMembership").isEmpty());
    }

    @Test void unverifiedAccountAndIncompleteOrUnsupportedDossiersPersistNoMembershipOrEvidence() throws Exception {
        String unverified = account(false);
        String tax = UUID.randomUUID().toString();
        http.perform(dossier("POST", "/api/companies/verification-request", form(tax)).header("Authorization", unverified))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(
                        "40335 EMAIL_VERIFICATION_REQUIRED: Verify your account email before recruiting"));
        String owner = account(true);
        long actor = data(http.perform(get("/api/auth/me").header("Authorization", owner)).andReturn()).path("id").asLong();
        var invalid = new java.util.HashMap<String,Object>(form(tax));
        invalid.remove("legal");
        http.perform(dossier("POST", "/api/companies/verification-request", invalid).header("Authorization", owner))
                .andExpect(status().isBadRequest());
        http.perform(multipart("/api/companies/verification-request")
                .file(new MockMultipartFile("dossier", "dossier.json", "application/json", json.writeValueAsBytes(form(tax))))
                .file(new MockMultipartFile("file", "file.pdf", "application/pdf", "not a PDF".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", owner)).andExpect(status().isBadRequest());
        http.perform(multipart("/api/companies/verification-request")
                .file(new MockMultipartFile("file", "file.pdf", "application/pdf", "%PDF-test".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", owner)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        byte[] oversized = new byte[10*1024*1024+1];
        http.perform(multipart("/api/companies/verification-request")
                .file(new MockMultipartFile("dossier", "dossier.json", "application/json", json.writeValueAsBytes(form(tax))))
                .file(new MockMultipartFile("file", "large.pdf", "application/pdf", oversized))
                .header("Authorization", owner)).andExpect(status().is(413));
        http.perform(get("/api/auth/me").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeMembership").isEmpty());
        assertThat(database.queryForObject("SELECT count(*) FROM company_verification_documents WHERE uploader_id=?", Long.class, actor)).isZero();
    }

    @Test void rejectionCorrectionCreatesDistinctEvidenceAndPreservesCompanyTaxAndDecisionHistory() throws Exception {
        String owner = account(true);
        String tax = UUID.randomUUID().toString();
        long id = data(http.perform(dossier("POST", "/api/companies/verification-request", form(tax))
                .header("Authorization", owner)).andExpect(status().isCreated()).andReturn()).path("id").asLong();
        String staff = account(false);
        long staffId = data(http.perform(get("/api/auth/me").header("Authorization", staff)).andReturn()).path("id").asLong();
        database.update("UPDATE users SET role='STAFF' WHERE id=?", staffId);
        String staffToken = "Bearer " + context.getBean(com.guardwork.backend.auth.service.JwtService.class)
                .generateAccessToken(staffId, "staff", "staff@example.vn", "STAFF");
        http.perform(put("/api/staff/companies/" + id + "/reject").header("Authorization", staffToken)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "expectedVersion", 0, "rejectionReason", "Bổ sung giấy phép hợp lệ"))))
                .andExpect(status().isOk());
        var correction = new java.util.HashMap<String,Object>(form("attempted tax replacement"));
        correction.put("name", "Công ty đã sửa"); correction.put("expectedVersion", 1);
        http.perform(dossier("PUT", "/api/companies/me/verification", correction).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.companyId").value(id))
                .andExpect(jsonPath("$.data.taxCode").value(tax)).andExpect(jsonPath("$.data.version").value(2));
        var history = data(http.perform(get("/api/companies/me/verification/history").header("Authorization", owner))
                .andExpect(status().isOk()).andReturn());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).path("outcome").asText()).isEqualTo("REJECTED");
        assertThat(history.get(0).path("reviewerId").asLong()).isEqualTo(staffId);
        assertThat(history.get(0).path("documentId").asText()).isNotEqualTo(history.get(1).path("documentId").asText());
        assertThat(history.get(0).path("companyName").asText()).isEqualTo("Công ty Ánh Dương");
        assertThat(history.get(1).path("companyName").asText()).isEqualTo("Công ty đã sửa");
        http.perform(dossier("PUT", "/api/companies/" + id + "/resubmit", correction).header("Authorization", owner))
                .andExpect(status().isBadRequest());
    }

    Map<String,Object> form(String tax) {
        return Map.of("name", "Công ty Ánh Dương", "taxCode", tax, "legal", Map.of(
                "workAddress", "Địa chỉ công ty", "submitterPosition", "Giám đốc",
                "representation", "LEGAL_REPRESENTATIVE", "declarationAccepted", true));
    }
    MockMultipartHttpServletRequestBuilder dossier(String method, String path, Map<String,Object> form) throws Exception {
        return multipart(org.springframework.http.HttpMethod.valueOf(method), path)
                .file(new MockMultipartFile("dossier", "dossier.json", "application/json", json.writeValueAsBytes(form)))
                .file(new MockMultipartFile("file", "giấy-phép.pdf", "application/pdf", "%PDF-test".getBytes(StandardCharsets.UTF_8)));
    }
    String account(boolean verifyEmail) throws Exception {
        String username = "co" + UUID.randomUUID().toString().replace("-", "");
        String email = username + "@example.vn";
        http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "firstName", "An", "lastName", "Nguyễn", "username", username, "email", email, "password", "GuardWork123!"))))
                .andExpect(status().isCreated());
        var login = data(http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("usernameOrEmail", username, "password", "GuardWork123!"))))
                .andExpect(status().isOk()).andReturn());
        String authorization = "Bearer " + login.path("accessToken").asText();
        if (verifyEmail) {
            http.perform(post("/api/me/email-verification/request").header("Authorization", authorization)).andExpect(status().isOk());
            http.perform(post("/api/me/email-verification/confirm").header("Authorization", authorization)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("emailSecret", proofs.get(email)))))
                    .andExpect(status().isOk());
        }
        return authorization;
    }
    JsonNode data(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
