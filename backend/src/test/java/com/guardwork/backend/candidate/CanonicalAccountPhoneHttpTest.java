package com.guardwork.backend.candidate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.onboarding.service.OnboardingSecrets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class CanonicalAccountPhoneHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate database;
    MockMvc http;
    static final String PATH = "/api/v1/candidates/profile";
    record Account(long id, String token) {}

    @BeforeEach
    void setup() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void contactChangesAndClearingUseUserAndKeepHistoricalProofUntouched() throws Exception {
        Account account = account();
        String oldPhone = phone();
        String newPhone = phone();
        putProfile(account, "Original", oldPhone).andExpect(status().isOk());
        database.update("UPDATE candidate_profiles SET phone_number=? WHERE user_id=?", oldPhone, account.id());
        database.update("INSERT INTO employer_profiles(user_id,applicant,full_name,email_verified_at,phone,phone_verified_at) VALUES (?,false,'Historical',now(),?,now())", account.id(), oldPhone);
        var history = database.queryForMap("SELECT phone,phone_verified_at FROM employer_profiles WHERE user_id=?", account.id());

        putProfile(account, "Changed", " " + newPhone + " ").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phoneNumber").value(OnboardingSecrets.phone(newPhone)));
        http.perform(get(PATH).header("Authorization", account.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phoneNumber").value(OnboardingSecrets.phone(newPhone)));
        http.perform(get("/api/me/employer-onboarding").header("Authorization", account.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.phone").value(OnboardingSecrets.phone(newPhone)))
                .andExpect(jsonPath("$.data.phoneVerified").doesNotExist());
        assertThat(database.queryForObject("SELECT phone_number FROM candidate_profiles WHERE user_id=?", String.class, account.id())).isEqualTo(oldPhone);
        assertThat(database.queryForMap("SELECT phone,phone_verified_at FROM employer_profiles WHERE user_id=?", account.id())).isEqualTo(history);
        putProfile(account, "Cleared", null).andExpect(status().isOk()).andExpect(jsonPath("$.data.phoneNumber").isEmpty());
        assertThat(database.queryForObject("SELECT phone_number FROM users WHERE id=?", String.class, account.id())).isNull();
        http.perform(get(PATH).header("Authorization", account.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phoneNumber").isEmpty());
        // A frozen historical verified phone does not reserve the released current contact.
        putProfile(account(), "Reused", oldPhone).andExpect(status().isOk());
    }

    @Test
    void duplicateAndInvalidChangesRollBackEveryProfileField() throws Exception {
        Account owner = account();
        Account other = account();
        String number = phone();
        String original = phone();
        putProfile(owner, "Owner", number).andExpect(status().isOk());
        putProfile(other, "Original", original).andExpect(status().isOk());
        putProfile(other, "Must roll back", OnboardingSecrets.phone(number)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("40930 IDENTIFIER_UNAVAILABLE: Contact information is unavailable"));
        for (String invalid : List.of("", "0123456789", "0241234567", "+840901234567")) {
            putProfile(other, "Invalid", invalid).andExpect(status().isBadRequest());
        }
        http.perform(get(PATH).header("Authorization", other.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("Original"))
                .andExpect(jsonPath("$.data.phoneNumber").value(OnboardingSecrets.phone(original)));
        Account fresh = account();
        putProfile(fresh, "Must not create a profile", number).andExpect(status().isConflict());
        assertThat(database.queryForObject("SELECT count(*) FROM candidate_profiles WHERE user_id=?", Long.class, fresh.id())).isZero();
    }

    @Test
    void concurrentAliasesHaveOneOwnerAndNoLosingProfileChanges() throws Exception {
        Account a = account();
        Account b = account();
        String number = phone();
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> responses = new ArrayList<>();
            for (var claim : List.of(Map.entry(a, number), Map.entry(b, OnboardingSecrets.phone(number)))) {
                responses.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return putProfile(claim.getKey(), "Claim", claim.getValue()).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            assertThat(List.of(responses.get(0).get(30, TimeUnit.SECONDS), responses.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(database.queryForObject("SELECT count(*) FROM users WHERE phone_number=?", Long.class, OnboardingSecrets.phone(number))).isEqualTo(1);
        assertThat(database.queryForObject("SELECT count(*) FROM candidate_profiles WHERE user_id IN (?,?)", Long.class, a.id(), b.id())).isEqualTo(1);
    }

    @Test
    void authenticationAndApplicantAuthorizationRemainRequired() throws Exception {
        http.perform(get(PATH)).andExpect(status().isUnauthorized());
        http.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        Account employer = account();
        database.update("INSERT INTO employer_profiles(user_id,applicant,full_name) VALUES (?,true,'Applicant')", employer.id());
        putProfile(employer, "Blocked", phone()).andExpect(status().isForbidden());
        assertThat(database.queryForObject("SELECT phone_number FROM users WHERE id=?", String.class, employer.id())).isNull();
    }

    ResultActions putProfile(Account account, String name, String phone) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("fullName", name); request.put("phoneNumber", phone); request.put("skills", List.of("Java"));
        return http.perform(put(PATH).header("Authorization", account.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)));
    }

    Account account() throws Exception {
        String username = UUID.randomUUID().toString().replace("-", "");
        String password = "GuardWorkTest123!";
        http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("firstName", "An", "lastName", "Nguyễn", "username", username,
                        "email", username + "@example.vn", "password", password))))
                .andExpect(status().isCreated());
        MvcResult result = http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("usernameOrEmail", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = json.readTree(result.getResponse().getContentAsString()).path("data");
        return new Account(database.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username),
                "Bearer " + data.path("accessToken").asText());
    }

    static String phone() {
        return "09%08d".formatted(Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000_000));
    }
}
