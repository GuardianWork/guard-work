package com.guardwork.backend.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.auth.service.JwtService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "guardwork.cors.allowed-origins=http://localhost:5137,http://localhost:5138")
class PortalAuthHttpTest {
    private static final String HEADER = "X-Guardian-Portal";
    private static final String PASSWORD = "PortalTest123!";
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate database;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtService jwt;
    MockMvc http;

    @BeforeEach
    void setUp() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @ParameterizedTest
    @CsvSource({"USER,public", "STAFF,staff"})
    void portalLoginRefreshAndLogoutKeepTheirContext(String role, String portal) throws Exception {
        String username = account(role);
        JsonNode login = login(username, portal);
        assertThat(jwt.parseAndValidateToken(login.path("accessToken").asText()).get("portal")).isEqualTo(portal);
        http.perform(get("/api/auth/me").header(HEADER, portal)
                .header("Authorization", "Bearer " + login.path("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value(role));
        String refresh = login.path("refreshToken").asText();
        assertThat(database.queryForObject("SELECT portal FROM refresh_tokens WHERE token_hash=?", String.class,
                jwt.hashToken(refresh))).isEqualTo(portal);
        var rotated = json.readTree(http.perform(post("/api/auth/refresh").header(HEADER, portal)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(rotated.path("refreshToken").asText()).isNotEqualTo(refresh);
        assertThat(jwt.parseAndValidateToken(rotated.path("accessToken").asText()).get("portal")).isEqualTo(portal);
        http.perform(post("/api/auth/logout").header(HEADER, portal).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", rotated.path("refreshToken").asText()))))
                .andExpect(status().isOk());
        http.perform(post("/api/auth/refresh").header(HEADER, portal).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", rotated.path("refreshToken").asText()))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
    }

    @ParameterizedTest
    @CsvSource({"USER,staff", "STAFF,public"})
    void portalHeaderCannotGrantRoleOrLogIntoTheOtherPortal(String role, String portal) throws Exception {
        String username = account(role);
        http.perform(post("/api/auth/login").header(HEADER, portal).contentType(MediaType.APPLICATION_JSON)
                .content(credentials(username)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void wrongPortalDoesNotConsumeRefreshOrLogoutTheSession() throws Exception {
        JsonNode login = login(account("STAFF"), "staff");
        String body = json.writeValueAsString(Map.of("refreshToken", login.path("refreshToken").asText()));
        for (String action : new String[]{"refresh", "logout"}) {
            for (boolean withBearer : new boolean[]{false, true}) {
                var request = post("/api/auth/" + action).header(HEADER, "public")
                        .contentType(MediaType.APPLICATION_JSON).content(body);
                if (withBearer) request.header("Authorization", "Bearer " + login.path("accessToken").asText());
                http.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
            }
        }
        http.perform(post("/api/auth/refresh").header(HEADER, "staff")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    @Test
    void staffAuthorizationUsesBearerRoleAndInvalidTokensReturn401() throws Exception {
        JsonNode user = login(account("USER"), "public");
        for (String authorization : new String[]{"Bearer invalid", "Bearer "}) {
            http.perform(get("/api/staff/companies/verifications").header(HEADER, "staff")
                    .header("Authorization", authorization))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        }
        http.perform(get("/api/staff/companies/verifications").header(HEADER, "staff"))
                .andExpect(status().isUnauthorized());
        http.perform(get("/api/staff/companies/verifications").header(HEADER, "staff")
                .header("Authorization", "Bearer " + user.path("accessToken").asText()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        JsonNode staff = login(account("STAFF"), "staff");
        http.perform(get("/api/staff/companies/verifications").header(HEADER, "staff")
                .header("Authorization", "Bearer " + staff.path("accessToken").asText()))
                .andExpect(status().isOk());
        http.perform(get("/api/auth/me").header(HEADER, "public")
                .header("Authorization", "Bearer " + staff.path("accessToken").asText()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void invalidOrDuplicatePortalHeadersAreRejected() throws Exception {
        String body = credentials(account("USER"));
        for (String value : new String[]{"admin", "", "public,staff"}) {
            http.perform(post("/api/auth/login").header(HEADER, value)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        }
        http.perform(post("/api/auth/login").header(HEADER, "public", "staff")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void directCrossOriginPreflightAllowsOnlyConfiguredFrontendOrigins() throws Exception {
        for (String origin : new String[]{"http://localhost:5137", "http://localhost:5138"}) {
            http.perform(options("/api/auth/login").header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type,authorization,x-guardian-portal"))
                    .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
        http.perform(options("/api/auth/login").header("Origin", "http://localhost:9999")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
    }

    private JsonNode login(String username, String portal) throws Exception {
        return json.readTree(http.perform(post("/api/auth/login").header(HEADER, portal)
                .contentType(MediaType.APPLICATION_JSON).content(credentials(username)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(200))
                .andReturn().getResponse().getContentAsString()).path("data");
    }

    private String credentials(String username) throws Exception {
        return json.writeValueAsString(Map.of("usernameOrEmail", username, "password", PASSWORD));
    }

    private String account(String role) {
        String username = "portal_" + UUID.randomUUID().toString().replace("-", "");
        database.update("INSERT INTO users(first_name,last_name,username,email,password,role) VALUES ('Portal','Tester',?,?,?,?)",
                username, username + "@example.vn", passwords.encode(PASSWORD), role);
        return username;
    }
}
