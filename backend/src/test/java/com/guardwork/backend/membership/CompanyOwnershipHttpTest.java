package com.guardwork.backend.membership;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class CompanyOwnershipHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate database;
    MockMvc http;

    @BeforeEach
    void setUp() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void invalidRegistrationFieldsDoNotCreateCompanyOrMembership() throws Exception {
        String token = account();
        Map<String, Object> valid = mapper.readValue(company(UUID.randomUUID().toString()),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        for (Map<String, Object> override : java.util.List.<Map<String, Object>>of(
                Map.of("name", "  "), Map.of("taxCode", "  "),
                Map.of("registrationCertificateUrl", "  "), Map.of("email", ""),
                Map.of("email", "person..name@example.vn"))) {
            Map<String, Object> body = new java.util.HashMap<>(valid);
            body.putAll(override);
            http.perform(post("/api/companies/verification-request").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.data").isEmpty());
        }
        http.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeMembership").isEmpty());
        assertThat(database.queryForObject("SELECT count(*) FROM companies WHERE tax_code=?",
                Long.class, valid.get("taxCode"))).isZero();
    }

    @Test
    void eligibleUserCreatesPendingCompanyAndSeesFreshOwnerContext() throws Exception {
        String token = account();
        http.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeMembership").isEmpty());
        http.perform(post("/api/companies/verification-request").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(company(UUID.randomUUID().toString())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.name").value("Công ty Giải Pháp Ánh Dương"))
                .andExpect(jsonPath("$.data.verificationStatus").value("PENDING"));
        http.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.activeMembership.companyName").value("Công ty Giải Pháp Ánh Dương"))
                .andExpect(jsonPath("$.data.activeMembership.companyRole").value("OWNER"))
                .andExpect(jsonPath("$.data.activeMembership.joinedAt").isNotEmpty());
    }

    @Test
    void ownerEntersPendingWorkspaceWhileUnrelatedAndAnonymousCallersRemainExcluded() throws Exception {
        String owner = account();
        String unrelated = account();
        String tax = UUID.randomUUID().toString();
        String body = http.perform(post("/api/companies/verification-request").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON).content(company(tax)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(body).path("data").path("id").asLong();
        http.perform(get("/api/companies/me").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.companyId").value(id))
                .andExpect(jsonPath("$.data.companyRole").value("OWNER"));
        http.perform(get("/api/companies/me/verification").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.verificationStatus").value("PENDING"));
        http.perform(get("/api/companies/me").header("Authorization", unrelated))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        for (String path : new String[]{"/api/companies/" + id, "/api/companies/tax-code/" + tax}) {
            http.perform(get(path).header("Authorization", owner)).andExpect(status().isOk());
            http.perform(get(path).header("Authorization", unrelated)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("40400 COMPANY_NOT_FOUND: Company not found"));
            http.perform(get(path)).andExpect(status().isUnauthorized());
        }
        http.perform(put("/api/companies/" + id + "/resubmit").header("Authorization", unrelated)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isNotFound());
        http.perform(post("/api/companies/verification-request").contentType(MediaType.APPLICATION_JSON).content(company(tax)))
                .andExpect(status().isUnauthorized());
        http.perform(get("/api/admin/companies").header("Authorization", owner)).andExpect(status().isForbidden());
    }

    @Test
    void oversizedRegistrationRemainsAValidationErrorAndUserCanRetry() throws Exception {
        String actor = account();
        String tax = UUID.randomUUID().toString();
        String invalid = mapper.writeValueAsString(Map.of("name", "Á".repeat(256), "taxCode", tax,
                "email", tax + "@company.vn", "registrationCertificateUrl", "https://storage.guardwork.vn/license.pdf"));
        http.perform(post("/api/companies/verification-request").header("Authorization", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("40000 BAD_REQUEST: Company name exceeds 255 characters"));
        http.perform(get("/api/auth/me").header("Authorization", actor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeMembership").isEmpty());
        http.perform(post("/api/companies/verification-request").header("Authorization", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(company(tax)))
                .andExpect(status().isCreated());
    }

    @Test
    void concurrentCreationsGiveOneOwnerAndLeaveNoCompanyForTheLosingRequest() throws Exception {
        String actor = account();
        String firstTax = UUID.randomUUID().toString();
        String secondTax = UUID.randomUUID().toString();
        String firstPayload = company(firstTax);
        String secondPayload = company(secondTax);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        int first;
        int second;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> racingCreation(actor, firstPayload, ready, start));
            var two = workers.submit(() -> racingCreation(actor, secondPayload, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first = one.get(10, TimeUnit.SECONDS);
            second = two.get(10, TimeUnit.SECONDS);
        }
        assertThat(java.util.List.of(first, second)).containsExactlyInAnyOrder(201, 409);
        http.perform(get("/api/auth/me").header("Authorization", actor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeMembership.companyRole").value("OWNER"));
        String losingTax = first == 409 ? firstTax : secondTax;
        http.perform(post("/api/companies/verification-request").header("Authorization", account())
                        .contentType(MediaType.APPLICATION_JSON).content(company(losingTax)))
                .andExpect(status().isCreated());
        http.perform(post("/api/companies/verification-request").header("Authorization", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(company(UUID.randomUUID().toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("40910 ACTIVE_COMPANY_MEMBERSHIP_EXISTS: Active Company membership already exists"));
    }

    @Test
    void failedAuditRollsBackCompanyAndMembershipTogether() throws Exception {
        String actor = account();
        long userId = userId(actor);
        String tax = UUID.randomUUID().toString();
        database.execute("CREATE FUNCTION gw002_reject_audit() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'controlled audit failure' USING ERRCODE='23514'; END; $$ LANGUAGE plpgsql");
        database.execute("CREATE TRIGGER gw002_reject_audit BEFORE INSERT ON company_membership_audit_events FOR EACH ROW WHEN (NEW.actor_user_id = " + userId + ") EXECUTE FUNCTION gw002_reject_audit()");
        try {
            http.perform(post("/api/companies/verification-request").header("Authorization", actor)
                            .contentType(MediaType.APPLICATION_JSON).content(company(tax)))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.data").isEmpty());
            http.perform(get("/api/auth/me").header("Authorization", actor))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeMembership").isEmpty());
        } finally {
            database.execute("DROP TRIGGER gw002_reject_audit ON company_membership_audit_events");
            database.execute("DROP FUNCTION gw002_reject_audit()");
        }
        // Same identity/tax code remains eligible, proving no partial Company or Owner survived.
        http.perform(post("/api/companies/verification-request").header("Authorization", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(company(tax))).andExpect(status().isCreated());
    }

    @Test
    void companyAndAccountSuspensionDenyWorkspaceEvenWithPreviouslyIssuedToken() throws Exception {
        String actor = account();
        long userId = userId(actor);
        long companyId = createdCompany(actor);
        database.update("UPDATE companies SET is_banned = true WHERE id = ?", companyId);
        http.perform(get("/api/companies/me").header("Authorization", actor)).andExpect(status().isNotFound());
        http.perform(get("/api/companies/me/verification").header("Authorization", actor)).andExpect(status().isNotFound());
        http.perform(get("/api/companies/" + companyId).header("Authorization", actor)).andExpect(status().isNotFound());
        database.update("UPDATE companies SET is_banned = false WHERE id = ?", companyId);
        database.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", userId);
        http.perform(get("/api/companies/me").header("Authorization", actor)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("40304 ACCOUNT_SUSPENDED: Account is suspended"));
        http.perform(post("/api/companies/verification-request").header("Authorization", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(company(UUID.randomUUID().toString())))
                .andExpect(status().isForbidden());
    }

    @Test
    void recruiterCannotReadOwnerDetailsAndInactivePeriodDoesNotPreventNewOwnership() throws Exception {
        String owner = account();
        long companyId = createdCompany(owner);
        String recruiter = account();
        long recruiterId = userId(recruiter);
        // Fixture represents admission by the later invitation ticket, without replacing HTTP authorization.
        database.update("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?,?,'RECRUITER','ACTIVE',now())", companyId, recruiterId);
        http.perform(get("/api/companies/me").header("Authorization", recruiter)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companyRole").value("RECRUITER"));
        http.perform(get("/api/companies/me/verification").header("Authorization", recruiter)).andExpect(status().isForbidden());
        http.perform(get("/api/companies/" + companyId).header("Authorization", recruiter)).andExpect(status().isNotFound());
        database.update("UPDATE company_memberships SET status='REMOVED', ended_at=now(), ended_by_user_id=? WHERE user_id=? AND status='ACTIVE'", userId(owner), recruiterId);
        http.perform(get("/api/auth/me").header("Authorization", recruiter)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeMembership").isEmpty());
        http.perform(get("/api/companies/" + companyId).header("Authorization", recruiter)).andExpect(status().isNotFound());
        http.perform(post("/api/companies/verification-request").header("Authorization", recruiter)
                        .contentType(MediaType.APPLICATION_JSON).content(company(UUID.randomUUID().toString())))
                .andExpect(status().isCreated());
        http.perform(get("/api/auth/me").header("Authorization", recruiter)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeMembership.companyRole").value("OWNER"));
    }

    @Test
    void companyIdentifiersNeverClaimLegacyOwnershipOrExposeExistingCompany() throws Exception {
        String tax = UUID.randomUUID().toString();
        long legacy = database.queryForObject("INSERT INTO companies(name,tax_code,email,registration_certificate_url) VALUES ('Công ty Di Sản',?,?, 'https://private.guardwork.vn/license.pdf') RETURNING id", Long.class, tax, tax + "@company.vn");
        String actor = account();
        http.perform(get("/api/companies/" + legacy).header("Authorization", actor)).andExpect(status().isNotFound());
        http.perform(get("/api/companies/tax-code/" + tax).header("Authorization", actor)).andExpect(status().isNotFound());
        http.perform(post("/api/companies/verification-request").header("Authorization", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(company(tax)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("40901 DUPLICATE_TAX_CODE: Company registration details already registered"));
        http.perform(get("/api/auth/me").header("Authorization", actor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeMembership").isEmpty());
    }

    @Test
    void requiredFieldsAndMailboxFormatAreValidatedWithoutCreatingMembership() throws Exception {
        String actor = account();
        for (String invalid : new String[]{"{}", "{\"name\":\"Ánh\"}",
                "{\"name\":\"Ánh\",\"taxCode\":\"123\",\"email\":\"invalid\",\"registrationCertificateUrl\":\"url\"}",
                "{\"name\":\"Ánh\",\"taxCode\":\"123\",\"email\":\"mail@example.vn\"}"}) {
            http.perform(post("/api/companies/verification-request").header("Authorization", actor)
                            .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
        http.perform(get("/api/auth/me").header("Authorization", actor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeMembership").isEmpty());
    }

    @Test
    void authenticatedCompatibilityAliasPreservesRejectedCorrectionAndVersionChecks() throws Exception {
        String owner = account();
        long id = createdCompany(owner);
        String correctedEmail = UUID.randomUUID() + "@example.vn";
        String alias = "/api/companies/" + id + "/resubmit";
        http.perform(put(alias).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        database.update("UPDATE companies SET verification_status='REJECTED', rejection_reason='Bổ sung giấy phép', version=1 WHERE id=?", id);
        http.perform(get("/api/companies/me/verification").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rejectionReason").value("Bổ sung giấy phép"));
        http.perform(put(alias).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isConflict());
        http.perform(put(alias).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"email\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        http.perform(put(alias).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"name\":\"Công ty Ánh Dương Mới\",\"email\":\"" + correctedEmail + "\",\"registrationCertificateUrl\":\"https://storage.guardwork.vn/v2.pdf\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.verificationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.name").value("Công ty Ánh Dương Mới"))
                .andExpect(jsonPath("$.data.email").value(correctedEmail))
                .andExpect(jsonPath("$.data.rejectionReason").isEmpty()).andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    void duplicateLegacyMailboxRowsReturnGenericConflictWithoutCreatingMembership() throws Exception {
        String email="legacy_"+UUID.randomUUID()+"@example.vn";
        for(int row=0;row<2;row++) database.update("INSERT INTO companies(name,tax_code,email,registration_certificate_url) VALUES ('Private legacy identity',?,?,'https://private.guardwork.vn/license.pdf')",UUID.randomUUID().toString(),email);
        String actor=account(),tax=UUID.randomUUID().toString();
        String payload=mapper.writeValueAsString(Map.of("name","Công ty Ánh","taxCode",tax,"email",email,
                "registrationCertificateUrl","https://storage.guardwork.vn/license.pdf"));
        String response=http.perform(post("/api/companies/verification-request").header("Authorization",actor)
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("40901 DUPLICATE_TAX_CODE: Company registration details already registered"))
                .andExpect(jsonPath("$.data").isEmpty()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("Private legacy identity",email);
        http.perform(get("/api/auth/me").header("Authorization",actor)).andExpect(jsonPath("$.data.activeMembership").isEmpty());
        http.perform(post("/api/companies/verification-request").header("Authorization",actor).contentType(MediaType.APPLICATION_JSON).content(company(tax)))
                .andExpect(status().isCreated());
    }

    int racingCreation(String actor, String payload, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("creation race did not start");
        return http.perform(post("/api/companies/verification-request").header("Authorization", actor)
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn().getResponse().getStatus();
    }

    long userId(String token) throws Exception {
        String body = http.perform(get("/api/auth/me").header("Authorization", token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("id").asLong();
    }

    long createdCompany(String token) throws Exception {
        String body = http.perform(post("/api/companies/verification-request").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(company(UUID.randomUUID().toString())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("id").asLong();
    }

    String account() throws Exception {
        String name = "user_" + UUID.randomUUID().toString().replace("-", "");
        String email = name + "@example.vn";
        http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("firstName", "Ánh", "lastName", "Nguyễn", "username", name,
                                "email", email, "password", "Password123"))))
                .andExpect(status().isCreated());
        String response = http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("usernameOrEmail", email, "password", "Password123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(response).path("data").path("accessToken").asText();
    }

    String company(String taxCode) throws Exception {
        return mapper.writeValueAsString(Map.of("name", "Công ty Giải Pháp Ánh Dương", "taxCode", taxCode,
                "email", taxCode + "@company.vn", "registrationCertificateUrl", "https://storage.guardwork.vn/giấy-phép.pdf"));
    }
}
