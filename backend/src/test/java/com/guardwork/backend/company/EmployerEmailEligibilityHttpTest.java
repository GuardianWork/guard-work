package com.guardwork.backend.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.onboarding.delivery.OnboardingDelivery;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Retained employer profiles must not revive the superseded onboarding gates. */
@SpringBootTest
class EmployerEmailEligibilityHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate database;
    @MockitoBean OnboardingDelivery delivery;
    final Map<String, String> mailbox = new ConcurrentHashMap<>();
    MockMvc http;

    @BeforeEach void setup() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(delivery.memberEmail(anyString(), anyString())).thenAnswer(call -> {
            mailbox.put(call.getArgument(0), call.getArgument(1));
            return true;
        });
    }

    @Test void retainedApplicantCanUseCandidateFeaturesAndVerifyThroughSharedEndpoints() throws Exception {
        String username = "retained_" + UUID.randomUUID().toString().replace("-", "");
        String email = username + "@example.vn";
        String password = "GuardWorkTest123!";
        http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("firstName", "An", "lastName", "Nguyễn", "username", username,
                        "email", email, "password", password)))).andExpect(status().isCreated());
        long id = database.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
        database.update("INSERT INTO employer_profiles(user_id,applicant,full_name) VALUES (?,true,'Nguyễn An')", id);
        var retained = database.queryForMap("SELECT * FROM employer_profiles WHERE user_id=?", id);
        var login = http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("usernameOrEmail", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        String token = "Bearer " + json.readTree(login.getResponse().getContentAsString()).path("data").path("accessToken").asText();
        http.perform(get("/api/v1/candidates/profile").header("Authorization", token)).andExpect(status().isOk());
        http.perform(get("/api/me/employer-onboarding").header("Authorization", token)).andExpect(status().isGone());
        http.perform(post("/api/me/email-verification/request").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deliveryAccepted").value(true));
        http.perform(post("/api/me/email-verification/confirm").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("emailSecret", mailbox.get(email)))))
                .andExpect(status().isOk());
        http.perform(get("/api/auth/me").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailVerified").value(true))
                .andExpect(jsonPath("$.data.recruitingEmailEligible").value(true));
        assertThat(database.queryForMap("SELECT * FROM employer_profiles WHERE user_id=?", id)).isEqualTo(retained);
    }
}
