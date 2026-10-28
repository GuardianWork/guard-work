package com.guardwork.backend.membership;

import static com.guardwork.backend.CompanyDossierFixtures.completeDossier;

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
class CompanyCorrectionHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate database;
    MockMvc http;

    @BeforeEach
    void setUp() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void ownerCorrectsUnicodeDossierWithoutReplacingCompanyOrOwnership() throws Exception {
        String owner = account();
        long id = createdCompany(owner);
        String updatedEmail = UUID.randomUUID() + "@example.vn";
        String tax = mapper.readTree(http.perform(get("/api/companies/me/verification").header("Authorization", owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("taxCode").asText();
        database.update("UPDATE companies SET verification_status='REJECTED', rejection_reason='Bổ sung giấy phép kinh doanh có chữ ký hợp lệ', version=1 WHERE id=?", id);
        com.guardwork.backend.CompanyDossierFixtures.completeFixtureReview(database, id);
        http.perform(get("/api/companies/me/verification").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rejectionReason").value("Bổ sung giấy phép kinh doanh có chữ ký hợp lệ"));
        http.perform(completeDossier("PUT", "/api/companies/me/verification", mapper.writeValueAsString(Map.of(
                                "expectedVersion", 1, "name", "  Công ty Ánh Dương Mới  ", "email", "  " + updatedEmail.toUpperCase(java.util.Locale.ROOT) + "  ",
                                "registrationCertificateUrl", "https://storage.guardwork.vn/giấy-phép-mới.pdf",
                                "taxCode", "attempted replacement", "ownerUserId", userId(account())))).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.companyId").value(id))
                .andExpect(jsonPath("$.data.name").value("Công ty Ánh Dương Mới"))
                .andExpect(jsonPath("$.data.email").value(mapper.readTree(http.perform(get("/api/auth/me").header("Authorization", owner)).andReturn().getResponse().getContentAsString()).path("data").path("email").asText()))
                .andExpect(jsonPath("$.data.registrationCertificateUrl").value(org.hamcrest.Matchers.startsWith("/api/company-verification-documents/")))
                .andExpect(jsonPath("$.data.taxCode").value(tax)).andExpect(jsonPath("$.data.verificationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.rejectionReason").isEmpty()).andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty()).andExpect(jsonPath("$.data.updatedAt").isNotEmpty());
        http.perform(get("/api/auth/me").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeMembership.companyId").value(id))
                .andExpect(jsonPath("$.data.activeMembership.companyRole").value("OWNER"));
    }

    @Test
    void invalidCorrectionCannotPartiallyOverwriteRejectedDossier() throws Exception {
        String owner = account();
        long id = createdCompany(owner);
        database.update("UPDATE companies SET verification_status='REJECTED', rejection_reason='Bổ sung bản chính giấy phép', version=1 WHERE id=?", id);
        com.guardwork.backend.CompanyDossierFixtures.completeFixtureReview(database, id);
        for (Map<String, Object> invalid : java.util.List.<Map<String, Object>>of(
                Map.of("expectedVersion", 1, "name", "Á".repeat(256)),
                Map.of("expectedVersion", 1, "name", "  "),
                Map.of("expectedVersion", 1, "registrationCertificateUrl", "  "),
                Map.of("name", "Không có phiên bản"))) {
            http.perform(completeDossier("PUT", "/api/companies/me/verification", mapper.writeValueAsString(invalid)).header("Authorization", owner)
                            )
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data").isEmpty());
            http.perform(get("/api/companies/me/verification").header("Authorization", owner))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Công ty Giải Pháp Ánh Dương"))
                    .andExpect(jsonPath("$.data.verificationStatus").value("REJECTED"))
                    .andExpect(jsonPath("$.data.rejectionReason").value("Bổ sung bản chính giấy phép"))
                    .andExpect(jsonPath("$.data.version").value(1));
        }
        http.perform(completeDossier("PUT", "/api/companies/" + id + "/resubmit", "{\"expectedVersion\":1}").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.companyId").value(id))
                .andExpect(jsonPath("$.data.id").doesNotExist()).andExpect(jsonPath("$.data.updatedAt").isNotEmpty());
    }

    @Test
    void ownerMustUseExistingRejectedCompanyAndConflictingCorrectionKeepsFeedback() throws Exception {
        String owner = account();
        String tax = UUID.randomUUID().toString();
        String registration = company(tax);
        long id = mapper.readTree(http.perform(completeDossier("POST", "/api/companies/verification-request", registration).header("Authorization", owner)
                ).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("data").path("id").asLong();
        database.update("UPDATE companies SET verification_status='REJECTED', rejection_reason='Bổ sung bản chính giấy phép', version=1 WHERE id=?", id);
        com.guardwork.backend.CompanyDossierFixtures.completeFixtureReview(database, id);
        http.perform(completeDossier("POST", "/api/companies/verification-request", registration).header("Authorization", owner))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(
                        "40902 RESUBMISSION_REQUIRED: Correct and resubmit your existing rejected Company"));
        http.perform(completeDossier("POST", "/api/companies/verification-request", registration).header("Authorization", account())
                        )
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(
                        "40901 DUPLICATE_TAX_CODE: Company registration details already registered"));
        http.perform(completeDossier("PUT", "/api/companies/me/verification", mapper.writeValueAsString(Map.of("expectedVersion", 0, "name", "Stale correction"))).header("Authorization", owner))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data").isEmpty());
        http.perform(get("/api/companies/me/verification").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.companyId").value(id))
                .andExpect(jsonPath("$.data.version").value(1)).andExpect(jsonPath("$.data.rejectionReason").value("Bổ sung bản chính giấy phép"));
    }

    @Test
    void forbiddenActorsCannotReadFeedbackOrCorrectEvenUsingKnownCompanyId() throws Exception {
        String owner = account();
        long id = rejectedCompany(owner);
        String unrelated = account();
        String recruiter = account();
        long recruiterId = userId(recruiter);
        database.update("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?,?,'RECRUITER','ACTIVE',now())", id, recruiterId);
        assertCorrectionDenied(recruiter, id, 403, 404);
        assertCorrectionDenied(unrelated, id, 403, 404);
        assertCorrectionDenied(account("STAFF"), id, 403, 404);
        database.update("UPDATE company_memberships SET status='REMOVED', ended_at=now(), ended_by_user_id=? WHERE user_id=? AND status='ACTIVE'", userId(owner), recruiterId);
        assertCorrectionDenied(recruiter, id, 403, 404);
        http.perform(get("/api/companies/me/verification")).andExpect(status().isUnauthorized());
        for (String path : new String[]{"/api/companies/me/verification", "/api/companies/" + id + "/resubmit"}) {
            http.perform(completeDossier("PUT", path, "{\"expectedVersion\":1}"))
                    .andExpect(status().isUnauthorized());
        }
        database.update("UPDATE companies SET is_banned=true WHERE id=?", id);
        http.perform(get("/api/companies/me/verification").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companySuspended").value(true));
        http.perform(completeDossier("PUT", "/api/companies/me/verification", "{\"expectedVersion\":1}").header("Authorization", owner))
                .andExpect(status().isNotFound());
        database.update("UPDATE companies SET is_banned=false WHERE id=?", id);
        long ownerId = userId(owner);
        database.update("UPDATE users SET status='SUSPENDED' WHERE id=?", ownerId);
        assertCorrectionDenied(owner, id, 403, 403);
        database.update("UPDATE users SET status='ACTIVE' WHERE id=?", ownerId);
        http.perform(get("/api/companies/me/verification").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verificationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.version").value(1)).andExpect(jsonPath("$.data.rejectionReason").value("Bổ sung bản chính giấy phép"));
    }

    @Test
    void staleVersionAndPendingOrVerifiedStateCannotOverwriteDossier() throws Exception {
        String owner = account();
        long id = rejectedCompany(owner);
        http.perform(completeDossier("PUT", "/api/companies/me/verification", "{\"expectedVersion\":0,\"name\":\"Tên cũ không được lưu\"}").header("Authorization", owner))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(
                        "40022 CONCURRENT_MODIFICATION: Version mismatch. Expected: 0, current: 1"));
        for (String state : new String[]{"PENDING", "VERIFIED"}) {
            database.update("UPDATE companies SET verification_status=? WHERE id=?", state, id);
            http.perform(completeDossier("PUT", "/api/companies/me/verification", "{\"expectedVersion\":1,\"name\":\"Không được đổi trạng thái\"}").header("Authorization", owner)
                            )
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(
                            "40012 COMPANY_NOT_REJECTED: Only REJECTED companies can be resubmitted. Current status: " + state));
            http.perform(get("/api/companies/me/verification").header("Authorization", owner)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("Công ty Giải Pháp Ánh Dương"))
                    .andExpect(jsonPath("$.data.version").value(1)).andExpect(jsonPath("$.data.verificationStatus").value(state));
        }
    }

    @Test
    void concurrentResubmissionsApplyOneCorrectionAndOneVersionIncrement() throws Exception {
        String owner = account();
        long id = rejectedCompany(owner);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> concurrentCorrection(owner, "Công ty sửa lần đầu", ready, start));
            var second = workers.submit(() -> concurrentCorrection(owner, "Công ty sửa lần sau", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 400);
        }
        String response = http.perform(get("/api/companies/me/verification").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companyId").value(id)).andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.verificationStatus").value("PENDING")).andExpect(jsonPath("$.data.rejectionReason").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(response).path("data").path("name").asText()).isIn("Công ty sửa lần đầu", "Công ty sửa lần sau");
    }

    @Test
    void administratorFeedbackAndModerationIdentitySurviveOwnerCorrection() throws Exception {
        String owner = account();
        long id = createdCompany(owner);
        String administrator = account("STAFF");
        long adminId = userId(administrator);
        http.perform(put("/api/staff/companies/" + id + "/reject").header("Authorization", administrator)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                                "expectedVersion", 0, "rejectionReason", "Giấy phép kinh doanh cần bổ sung chữ ký hợp lệ"))))
                .andExpect(status().isOk());
        http.perform(get("/api/companies/me/verification").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verificationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectionReason").value("Giấy phép kinh doanh cần bổ sung chữ ký hợp lệ"))
                .andExpect(jsonPath("$.data.version").value(1));
        String before = http.perform(get("/api/companies/" + id).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verifiedBy").value(adminId)).andReturn().getResponse().getContentAsString();
        http.perform(completeDossier("PUT", "/api/companies/me/verification", "{\"expectedVersion\":1,\"name\":\"Công ty Giấy Phép Đã Sửa\"}").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.verificationStatus").value("PENDING"));
        http.perform(get("/api/companies/" + id).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verifiedBy").value(adminId))
                .andExpect(jsonPath("$.data.verifiedAt").value(mapper.readTree(before).path("data").path("verifiedAt").asText()));
    }

    @Test
    void companyChangesCommittedWhileCorrectionWaitsAreRecheckedBeforeAnyWrite() throws Exception {
        for (String change : new String[]{"version=2", "verification_status='VERIFIED', version=2", "is_banned=true"}) {
            String owner = account();
            long id = rejectedCompany(owner);
            try (var connection = database.getDataSource().getConnection(); var workers = Executors.newSingleThreadExecutor()) {
                connection.setAutoCommit(false);
                int blocker;
                try (var sql = connection.createStatement()) {
                    try (var result = sql.executeQuery("SELECT pg_backend_pid()")) {
                        result.next();
                        blocker = result.getInt(1);
                    }
                    sql.executeUpdate("UPDATE companies SET " + change + " WHERE id=" + id);
                }
                var correction = workers.submit(() -> http.perform(completeDossier("PUT", "/api/companies/me/verification", "{\"expectedVersion\":1,\"name\":\"Không được ghi đè\"}").header("Authorization", owner)
                                )
                        .andReturn().getResponse().getStatus());
                try {
                    awaitBlockedRequest(blocker);
                    assertThat(correction.isDone()).isFalse();
                } finally {
                    connection.commit();
                }
                int expected = change.startsWith("version") ? 409 : change.startsWith("verification") ? 400 : 404;
                assertThat(correction.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
            }
            database.update("UPDATE companies SET is_banned=false WHERE id=?", id);
            http.perform(get("/api/companies/me/verification").header("Authorization", owner)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("Công ty Giải Pháp Ánh Dương"));
        }
    }

    @Test
    void suspendedAccountCommittedWhileCorrectionWaitsCannotUseItsOldToken() throws Exception {
        String owner = account();
        long id = rejectedCompany(owner);
        long actorId = userId(owner);
        try (var connection = database.getDataSource().getConnection(); var workers = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            int blocker;
            try (var sql = connection.createStatement()) {
                try (var result = sql.executeQuery("SELECT pg_backend_pid()")) {
                    result.next();
                    blocker = result.getInt(1);
                }
                sql.executeUpdate("UPDATE users SET status='SUSPENDED' WHERE id=" + actorId);
            }
            var correction = workers.submit(() -> http.perform(completeDossier("PUT", "/api/companies/me/verification", "{\"expectedVersion\":1,\"name\":\"Không được lưu từ tài khoản đình chỉ\"}").header("Authorization", owner)
                            )
                    .andReturn().getResponse().getStatus());
            try {
                awaitBlockedRequest(blocker);
                assertThat(correction.isDone()).isFalse();
            } finally {
                connection.commit();
            }
            assertThat(correction.get(10, TimeUnit.SECONDS)).isEqualTo(403);
        }
        database.update("UPDATE users SET status='ACTIVE' WHERE id=?", actorId);
        http.perform(get("/api/companies/me/verification").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companyId").value(id)).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.verificationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.name").value("Công ty Giải Pháp Ánh Dương"));
    }

    void awaitBlockedRequest(int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (database.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", Integer.class, blocker) > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Correction did not wait on the held authorization guard");
    }

    int concurrentCorrection(String owner, String name, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Correction race did not start");
        return http.perform(completeDossier("PUT", "/api/companies/me/verification", mapper.writeValueAsString(Map.of("expectedVersion", 1, "name", name))).header("Authorization", owner))
                .andReturn().getResponse().getStatus();
    }

    void assertCorrectionDenied(String actor, long id, int scopedStatus, int aliasStatus) throws Exception {
        http.perform(get("/api/companies/me/verification").header("Authorization", actor)).andExpect(status().is(scopedStatus))
                .andExpect(jsonPath("$.data").isEmpty());
        http.perform(completeDossier("PUT", "/api/companies/me/verification", "{\"expectedVersion\":1}").header("Authorization", actor)
                        )
                .andExpect(status().is(scopedStatus)).andExpect(jsonPath("$.data").isEmpty());
        http.perform(completeDossier("PUT", "/api/companies/" + id + "/resubmit", "{\"expectedVersion\":1}").header("Authorization", actor)
                        )
                .andExpect(status().is(aliasStatus)).andExpect(jsonPath("$.data").isEmpty());
    }

    long rejectedCompany(String owner) throws Exception {
        long id = createdCompany(owner);
        database.update("UPDATE companies SET verification_status='REJECTED', rejection_reason='Bổ sung bản chính giấy phép', version=1 WHERE id=?", id);
        com.guardwork.backend.CompanyDossierFixtures.completeFixtureReview(database, id);
        return id;
    }

    long userId(String token) throws Exception {
        String body = http.perform(get("/api/auth/me").header("Authorization", token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("id").asLong();
    }

    long createdCompany(String token) throws Exception {
        String body = http.perform(completeDossier("POST", "/api/companies/verification-request", company(UUID.randomUUID().toString())).header("Authorization", token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("id").asLong();
    }

    String account() throws Exception {
        return account("USER");
    }

    String account(String role) throws Exception {
        String name = "user_" + UUID.randomUUID().toString().replace("-", "");
        String email = name + "@example.vn";
        http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("firstName", "Ánh", "lastName", "Nguyễn", "username", name,
                                "email", email, "password", "Password123"))))
                .andExpect(status().isCreated());
        if (!role.equals("USER")) database.update("UPDATE users SET role=? WHERE email=?", role, email);
        database.update("UPDATE users SET recruiting_email_verification_required=FALSE WHERE email=?", email);
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
