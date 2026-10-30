package com.guardwork.backend.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.onboarding.delivery.OnboardingDelivery;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class RetiredOnboardingHttpTest {
    static final String RETIRED = "41030 ONBOARDING_RETIRED: Use shared registration and Company setup";
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate database;
    @MockitoBean OnboardingDelivery delivery;
    MockMvc http;

    @BeforeEach void setup() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void retiredRoutesReturnGoneWithoutBindingObsoleteBodiesOrChangingData() throws Exception {
        String actor = account();
        List<Long> before = counts();
        for (String path : List.of("/api/employer-registrations",
                "/api/employer-registrations/not-a-uuid/email/resend",
                "/api/employer-registrations/not-a-uuid/email/verify")) {
            gone(request(HttpMethod.POST, path).contentType(MediaType.APPLICATION_JSON).content("{"));
        }
        for (var route : List.of(
                new Route(HttpMethod.GET, "/api/me/employer-onboarding"),
                new Route(HttpMethod.POST, "/api/me/employer-onboarding/email/send"),
                new Route(HttpMethod.POST, "/api/me/employer-onboarding/email/verify"),
                new Route(HttpMethod.POST, "/api/me/employer-onboarding/phone/send"),
                new Route(HttpMethod.POST, "/api/me/employer-onboarding/phone/verify"),
                new Route(HttpMethod.PUT, "/api/me/employer-onboarding/company"),
                new Route(HttpMethod.POST, "/api/me/employer-onboarding/documents"),
                new Route(HttpMethod.POST, "/api/companies/verification-request"),
                new Route(HttpMethod.PUT, "/api/companies/me/verification"),
                new Route(HttpMethod.PUT, "/api/companies/not-an-id/resubmit"))) {
            var anonymous = request(route.method(), route.path()).contentType(MediaType.APPLICATION_JSON).content("{");
            http.perform(anonymous).andExpect(status().isUnauthorized());
            gone(request(route.method(), route.path()).header("Authorization", actor)
                    .contentType(MediaType.APPLICATION_JSON).content("{"));
        }
        assertThat(counts()).isEqualTo(before);
        verifyNoInteractions(delivery);
    }

    @Test void retainedPendingRegistrationCannotPromoteAndDoesNotPreventExplicitSharedSignup() throws Exception {
        UUID registration = UUID.randomUUID();
        String email = "retained_" + registration + "@example.vn";
        String continuation = com.guardwork.backend.onboarding.service.OnboardingSecrets.token();
        String secret = com.guardwork.backend.onboarding.service.OnboardingSecrets.token();
        database.update("""
                INSERT INTO employer_registrations(id,continuation_hash,email,password_hash,full_name,gender,
                    phone,company_name,work_address,email_secret_hash,email_expires_at,email_sent_at,created_at)
                VALUES (?,?,?,?,?,'UNDISCLOSED','+84912345678','Retained Company','Retained address',?,
                    now()+interval '1 day',now(),now())
                """, registration, com.guardwork.backend.onboarding.service.OnboardingSecrets.hash(continuation),
                email, context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class).encode("GuardWork123!"),
                "Nguyễn An", com.guardwork.backend.onboarding.service.OnboardingSecrets.hash(secret));
        var retained = database.queryForMap("SELECT * FROM employer_registrations WHERE id=?", registration);
        gone(post("/api/employer-registrations/" + registration + "/email/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("continuationToken", continuation,
                        "emailSecret", secret, "password", "GuardWork123!"))));
        gone(post("/api/employer-registrations/" + registration + "/email/resend").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("continuationToken", continuation))));
        assertThat(database.queryForObject("SELECT count(*) FROM users WHERE email=?", Long.class, email)).isZero();
        assertThat(database.queryForMap("SELECT * FROM employer_registrations WHERE id=?", registration)).isEqualTo(retained);
        String token = account(email);
        http.perform(get("/api/auth/me").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailVerified").value(false))
                .andExpect(jsonPath("$.data.activeMembership").isEmpty());
        assertThat(database.queryForMap("SELECT * FROM employer_registrations WHERE id=?", registration)).isEqualTo(retained);
        verifyNoInteractions(delivery);
    }

    @Test void tombstonesPreserveActiveAccountChecksAndDoNotExemptMultipartSubmission() throws Exception {
        String token = account();
        var form = Map.of("name", "Company", "taxCode", UUID.randomUUID().toString(), "legal", Map.of(
                "workAddress", "Address", "submitterPosition", "Owner", "representation", "LEGAL_REPRESENTATIVE",
                "declarationAccepted", true));
        http.perform(multipart("/api/companies/verification-request")
                .file(new org.springframework.mock.web.MockMultipartFile("dossier", "dossier.json", "application/json", json.writeValueAsBytes(form)))
                .file(new org.springframework.mock.web.MockMultipartFile("file", "license.pdf", "application/pdf", "%PDF-test".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("Authorization", token).header("X-Guardian-Portal", "public"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(
                        "40335 EMAIL_VERIFICATION_REQUIRED: Verify your account email before recruiting"));
        long id = json.readTree(http.perform(get("/api/auth/me").header("Authorization", token))
                .andReturn().getResponse().getContentAsString()).path("data").path("id").asLong();
        database.update("UPDATE users SET status='SUSPENDED' WHERE id=?", id);
        for (var route : List.of(new Route(HttpMethod.GET, "/api/me/employer-onboarding"),
                new Route(HttpMethod.POST, "/api/companies/verification-request"))) {
            http.perform(request(route.method(), route.path()).header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    void gone(MockHttpServletRequestBuilder request) throws Exception {
        http.perform(request).andExpect(status().isGone()).andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.message").value(RETIRED)).andExpect(jsonPath("$.data").isEmpty());
    }

    List<Long> counts() {
        return List.of("users", "employer_registrations", "employer_profiles", "companies",
                "company_memberships", "company_verification_documents", "company_verification_attempts")
                .stream().map(table -> database.queryForObject("SELECT count(*) FROM " + table, Long.class)).toList();
    }

    String account() throws Exception {
        return account("retire_" + UUID.randomUUID() + "@example.vn");
    }

    String account(String email) throws Exception {
        String name = "retire_" + UUID.randomUUID().toString().replace("-", "");
        http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("firstName", "An", "lastName", "Nguyễn",
                        "username", name, "email", email, "password", "GuardWork123!"))))
                .andExpect(status().isCreated());
        JsonNode login = json.readTree(http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("usernameOrEmail", name, "password", "GuardWork123!"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        return "Bearer " + login.path("accessToken").asText();
    }

    record Route(HttpMethod method, String path) {}
}
