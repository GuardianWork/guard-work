package com.guardwork.backend.staff;

import static com.guardwork.backend.CompanyDossierFixtures.completeDossier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.auth.service.JwtService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

@SpringBootTest
class StaffVerificationHttpTest {
    private static final String PASSWORD = "StaffTest123!";
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate database;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder passwords;
    MockMvc http;

    @BeforeEach
    void setUp() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void staffLoginAndRefreshExposeStaffRoleAndPermitVerificationQueue() throws Exception {
        Account staff = account("STAFF");
        var login = json.readTree(http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("usernameOrEmail", staff.username(), "password", PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.role").value("STAFF"))
                .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(jwt.extractRole(login.path("accessToken").asText())).isEqualTo("STAFF");
        http.perform(get("/api/staff/companies/verifications")
                .header("Authorization", "Bearer " + login.path("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pageNumber").value(0));
        http.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", login.path("refreshToken").asText()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.role").value("STAFF"));
    }

    @Test
    void onlyStaffCanReviewAndClientSuppliedRolesCannotGrantAuthority() throws Exception {
        Account user = account("USER");
        Account staff = account("STAFF");
        String legacyToken = "Bearer " + jwt.generateAccessToken(staff.id(), staff.username(), staff.email(), "ADMIN");
        for (String authorization : new String[]{user.token(), legacyToken}) {
            http.perform(get("/api/staff/companies/verifications").header("Authorization", authorization)
                    .header("X-Staff-Role", "STAFF").header("X-Admin-Role", "ADMIN"))
                    .andExpect(status().isForbidden());
            http.perform(get("/api/staff/companies/1/verification-history").header("Authorization", authorization))
                    .andExpect(status().isForbidden());
            for (String action : new String[]{"verify", "reject"}) {
                http.perform(put("/api/staff/companies/1/" + action).header("Authorization", authorization)
                        .header("X-Staff-Id", staff.id()).header("X-Staff-Role", "STAFF")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"VERIFIED\",\"expectedVersion\":0}"))
                        .andExpect(status().isForbidden());
            }
        }
        http.perform(get("/api/staff/companies/verifications").header("X-Staff-Role", "STAFF"))
                .andExpect(status().isUnauthorized());
        http.perform(get("/api/staff/companies/1/verification-history")).andExpect(status().isUnauthorized());
        for (String action : new String[]{"verify", "reject"}) {
            http.perform(put("/api/staff/companies/1/" + action).header("X-Staff-Id", staff.id())
                    .header("X-Staff-Role", "STAFF").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void staffDecisionsKeepVersionChecksAndAttributeAuditToAuthenticatedActor() throws Exception {
        Account staff = account("STAFF");
        Account other = account("USER");
        long approved = company();
        long rejected = company();
        http.perform(get("/api/staff/companies/" + approved + "/verification-history")
                .header("Authorization", staff.token())).andExpect(status().isOk());
        http.perform(put("/api/staff/companies/" + approved + "/verify").header("Authorization", staff.token())
                .header("X-Admin-Id", other.id()).header("X-Staff-Id", other.id())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"VERIFIED\",\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.data.verifiedBy").value(staff.id()))
                .andExpect(jsonPath("$.data.version").value(1));
        http.perform(put("/api/staff/companies/" + rejected + "/reject").header("Authorization", staff.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rejectionReason\":\"Registration details do not match\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict());
        http.perform(put("/api/staff/companies/" + rejected + "/reject").header("Authorization", staff.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rejectionReason\":\"Registration details do not match\",\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.verificationStatus").value("REJECTED"));
        assertThat(database.queryForObject("SELECT verified_by FROM companies WHERE id=?", Long.class, approved))
                .isEqualTo(staff.id());
        assertThat(database.queryForObject("SELECT count(*) FROM audit_logs WHERE admin_id=? AND target_id IN (?,?)",
                Long.class, staff.id(), String.valueOf(approved), String.valueOf(rejected))).isEqualTo(2);
    }

    @Test
    void companyAdminMembershipRemainsAdminAndDoesNotGrantStaffAccess() throws Exception {
        Account owner = account("USER");
        Account member = account("USER");
        var response = http.perform(completeDossier("POST", "/api/companies/verification-request", json.writeValueAsString(Map.of(
                        "name", "Company role boundary", "taxCode", UUID.randomUUID().toString(),
                        "email", UUID.randomUUID() + "@company.vn", "registrationCertificateUrl", "https://example.vn/license.pdf"))).header("Authorization", owner.token())
                )
                .andExpect(status().isCreated()).andReturn();
        long company = json.readTree(response.getResponse().getContentAsString()).path("data").path("id").asLong();
        database.update("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?,?,'ADMIN','ACTIVE',now())",
                company, member.id());
        http.perform(get("/api/auth/me").header("Authorization", member.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.activeMembership.companyRole").value("ADMIN"));
        http.perform(get("/api/staff/companies/verifications").header("Authorization", member.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void swaggerListsStaffRoutesAndRetiredAdminRoutesHaveNoHandler() throws Exception {
        var docs = json.readTree(http.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var paths = docs.path("paths");
        for (String route : new String[]{"/api/staff/companies/verifications", "/api/staff/companies/{id}/verify",
                "/api/staff/companies/{id}/reject", "/api/staff/companies/{id}/verification-history"}) {
            assertThat(paths.has(route)).as(route).isTrue();
        }
        paths.fieldNames().forEachRemaining(path -> assertThat(path).doesNotStartWith("/api/admin/"));
        Account staff = account("STAFF");
        for (String path : new String[]{"/api/admin/companies/verifications", "/api/admin/companies/1/verification-history"}) {
            http.perform(get(path).header("Authorization", staff.token())).andExpect(status().isNotFound());
        }
        for (String action : new String[]{"verify", "reject"}) {
            http.perform(put("/api/admin/companies/1/" + action).header("Authorization", staff.token())
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
        }
    }

    Account account(String role) {
        String username = "staff_" + UUID.randomUUID().toString().replace("-", "");
        String email = username + "@example.vn";
        long id = database.queryForObject("INSERT INTO users(first_name,last_name,username,email,password,role) VALUES ('Staff','Tester',?,?,?,?) RETURNING id",
                Long.class, username, email, passwords.encode(PASSWORD), role);
        return new Account(id, username, email, "Bearer " + jwt.generateAccessToken(id, username, email, role));
    }

    long company() {
        return database.queryForObject("INSERT INTO companies(name,tax_code,registration_certificate_url) VALUES ('Company dossier',?,'https://example.vn/license.pdf') RETURNING id",
                Long.class, UUID.randomUUID().toString());
    }

    record Account(long id, String username, String email, String token) {}
}
