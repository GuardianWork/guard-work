package com.guardwork.backend.membership;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.membership.operations.LegacyOwnershipCommand;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class LegacyOwnershipCommandTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate database;
    @Autowired LegacyOwnershipCommand command;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    MockMvc http;
    record Account(long id, String token) { }
    @BeforeEach void setup() { http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test void separateAuthorizedReviewerAndOperatorAssignLegacyOwnerWithoutChangingVerification() throws Exception {
        Account applicant = account(false), reviewer = account(true), operator = account(true);
        long company = legacy(false);
        http.perform(get("/api/companies/" + company).header("Authorization", "Bearer " + applicant.token()))
                .andExpect(status().isNotFound());
        String reference = UUID.randomUUID().toString();
        assertThat(command.review(reviewer.token(), approved(reference, company, applicant.id(), false))).isEqualTo(reference);
        var result = command.assign(operator.token(), reference);
        assertThat(result.companyId()).isEqualTo(company);
        assertThat(result.applicantUserId()).isEqualTo(applicant.id());
        assertThat(result.reviewerUserId()).isEqualTo(reviewer.id());
        assertThat(result.operatorUserId()).isEqualTo(operator.id());
        assertThat(result.evidenceType()).isEqualTo("LEGAL_REPRESENTATIVE_REGISTRATION");
        assertThat(result.decision()).isEqualTo("APPROVED");
        assertThat(result.outcome()).isEqualTo("ASSIGNED");
        http.perform(get("/api/auth/me").header("Authorization", "Bearer " + applicant.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeMembership.companyId").value(company))
                .andExpect(jsonPath("$.data.activeMembership.companyRole").value("OWNER"));
        http.perform(get("/api/companies/me/verification").header("Authorization", "Bearer " + applicant.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.verificationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectionReason").value("Bổ sung giấy phép"))
                .andExpect(jsonPath("$.data.taxCode").isNotEmpty()).andExpect(jsonPath("$.data.version").value(8));
    }

    @Test void staleEvidenceCannotBecomeRecordedApproval() throws Exception {
        Account applicant = account(false), reviewer = account(true);
        long company = legacy(false);
        var expired = new LegacyOwnershipCommand.Review(UUID.randomUUID().toString(), company, applicant.id(),
                7L, "REJECTED", false, "LEGAL_REPRESENTATIVE_REGISTRATION", true, true, true, false,
                "2000-01-01T00:00:00Z");
        assertThatThrownBy(() -> command.review(reviewer.token(), expired)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Current evidence");
    }

    @Test void operatorCanVerifyPersistedAuditMetadataWithoutExposingEvidenceDocuments() throws Exception {
        Account applicant = account(false), reviewer = account(true), operator = account(true);
        long company = legacy(false);
        String reference = UUID.randomUUID().toString();
        command.review(reviewer.token(), approved(reference, company, applicant.id(), false));
        var result = command.assign(operator.token(), reference);
        var report = command.inspect(operator.token(), reference);
        assertThat(report.caseReference()).isEqualTo(reference);
        assertThat(report.membershipId()).isEqualTo(result.membershipId());
        assertThat(report.reviewerUserId()).isEqualTo(reviewer.id());
        assertThat(report.operatorUserId()).isEqualTo(operator.id());
        assertThat(report.decision()).isEqualTo("APPROVED");
        assertThat(report.evidenceType()).isEqualTo("LEGAL_REPRESENTATIVE_REGISTRATION");
        assertThat(report.reviewedAt()).isNotBlank();
        assertThat(report.assignedAt()).isNotBlank();
        assertThat(report.outcome()).isEqualTo("ASSIGNED");
        assertThatThrownBy(() -> command.inspect(applicant.token(), reference)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void identifiersAndUserClaimsCannotReplaceAuthenticatedReviewerOrSeparateActiveOperator() throws Exception {
        Account applicant = account(false), reviewer = account(true), operator = account(true);
        long company = legacy(false);
        String reference = UUID.randomUUID().toString();
        var review = approved(reference, company, applicant.id(), false);
        assertThatThrownBy(() -> command.review("not-a-valid-token", review)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command.review(applicant.token(), review)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command.assign(operator.token(), reference)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Recorded approval");
        command.review(reviewer.token(), review);
        assertThatThrownBy(() -> command.assign(reviewer.token(), reference)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
        assertThatThrownBy(() -> command.assign(applicant.token(), reference)).isInstanceOf(IllegalArgumentException.class);
        database.update("UPDATE users SET role='USER' WHERE id=?", operator.id());
        assertThatThrownBy(() -> command.assign(operator.token(), reference)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Staff");
        database.update("UPDATE users SET role='STAFF', status='SUSPENDED' WHERE id=?", operator.id());
        assertThatThrownBy(() -> command.assign(operator.token(), reference)).isInstanceOf(IllegalArgumentException.class);
        database.update("UPDATE users SET status='ACTIVE' WHERE id=?", operator.id());
        database.update("UPDATE users SET status='SUSPENDED' WHERE id=?", reviewer.id());
        assertThatThrownBy(() -> command.assign(operator.token(), reference)).isInstanceOf(IllegalArgumentException.class);
        database.update("UPDATE users SET status='ACTIVE' WHERE id=?", reviewer.id());
        command.assign(operator.token(), reference);
        http.perform(get("/api/companies/me").header("Authorization", "Bearer "+applicant.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.companyId").value(company));
    }

    @Test void insufficientContradictoryAndMismatchedReviewsLeaveLegacyCompanyUnavailable() throws Exception {
        Account applicant = account(false), reviewer = account(true), operator = account(true);
        long company = legacy(false);
        for (var invalid : java.util.List.of(
                new LegacyOwnershipCommand.Review(UUID.randomUUID().toString(), company, applicant.id(), 7L, "REJECTED", false,
                        "DOMAIN_EMAIL", true, true, true, false, null),
                new LegacyOwnershipCommand.Review(UUID.randomUUID().toString(), company, applicant.id(), 7L, "REJECTED", false,
                        "LEGAL_REPRESENTATIVE_AUTHORIZATION", false, true, true, false, null),
                new LegacyOwnershipCommand.Review(UUID.randomUUID().toString(), company, applicant.id(), 7L, "REJECTED", false,
                        "LEGAL_REPRESENTATIVE_REGISTRATION", true, false, true, false, null),
                new LegacyOwnershipCommand.Review(UUID.randomUUID().toString(), company, applicant.id(), 7L, "REJECTED", false,
                        "LEGAL_REPRESENTATIVE_REGISTRATION", true, true, false, false, null),
                new LegacyOwnershipCommand.Review(UUID.randomUUID().toString(), company, applicant.id(), 7L, "REJECTED", false,
                        "LEGAL_REPRESENTATIVE_REGISTRATION", true, true, true, true, null),
                new LegacyOwnershipCommand.Review(UUID.randomUUID().toString(), company, applicant.id(), 6L, "REJECTED", false,
                        "LEGAL_REPRESENTATIVE_REGISTRATION", true, true, true, false, null),
                new LegacyOwnershipCommand.Review(UUID.randomUUID().toString(), company, applicant.id(), 7L, "VERIFIED", false,
                        "LEGAL_REPRESENTATIVE_REGISTRATION", true, true, true, false, null))) {
            assertThatThrownBy(() -> command.review(reviewer.token(), invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> command.assign(operator.token(), invalid.caseReference())).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Recorded approval");
        }
        http.perform(get("/api/auth/me").header("Authorization", "Bearer "+applicant.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeMembership").isEmpty());
        http.perform(get("/api/companies/"+company).header("Authorization", "Bearer "+applicant.token())).andExpect(status().isNotFound());
        String reference = UUID.randomUUID().toString();
        var valid = new LegacyOwnershipCommand.Review(reference, company, applicant.id(), 7L, "REJECTED", false,
                "LEGAL_REPRESENTATIVE_AUTHORIZATION", true, true, true, false, null);
        command.review(reviewer.token(), valid);
        command.assign(operator.token(), reference);
        assertThat(command.inspect(operator.token(), reference).evidenceType()).isEqualTo("LEGAL_REPRESENTATIVE_AUTHORIZATION");
    }

    @Test void changedCompanySnapshotRequiresFreshIndependentReviewAndPreservesSuspension() throws Exception {
        Account applicant = account(false), reviewer = account(true), operator = account(true);
        long company = legacy(false);
        String stale = UUID.randomUUID().toString();
        command.review(reviewer.token(), approved(stale, company, applicant.id(), false));
        // Simulate a concurrent moderation change even when an old writer did not increment version.
        database.update("UPDATE companies SET is_banned=true, updated_at=now() WHERE id=?", company);
        assertThatThrownBy(() -> command.assign(operator.token(), stale)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state changed");
        assertThatThrownBy(() -> command.inspect(operator.token(), stale)).isInstanceOf(IllegalArgumentException.class);
        String fresh = UUID.randomUUID().toString();
        command.review(reviewer.token(), approved(fresh, company, applicant.id(), true));
        command.assign(operator.token(), fresh);
        http.perform(get("/api/companies/me").header("Authorization", "Bearer "+applicant.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.companySuspended").value(true));
        http.perform(get("/api/companies/me/invitations").header("Authorization", "Bearer "+applicant.token()))
                .andExpect(status().isNotFound());
        database.update("UPDATE companies SET is_banned=false WHERE id=?", company);
        http.perform(get("/api/companies/me/verification").header("Authorization", "Bearer "+applicant.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.verificationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectionReason").value("Bổ sung giấy phép"));
    }

    @Test void expiredRecordedAuthorityCannotAssignAndFreshCaseCanBeReviewed() throws Exception {
        Account applicant = account(false), reviewer = account(true), operator = account(true);
        long company = legacy(false);
        String stale = UUID.randomUUID().toString();
        var expiry = java.time.Instant.now().plusSeconds(1);
        command.review(reviewer.token(), new LegacyOwnershipCommand.Review(stale, company, applicant.id(), 7L, "REJECTED", false,
                "LEGAL_REPRESENTATIVE_AUTHORIZATION", true, true, true, false, expiry.toString()));
        while (java.time.Instant.now().isBefore(expiry)) Thread.sleep(20);
        assertThatThrownBy(() -> command.assign(operator.token(), stale)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Current evidence");
        String fresh = UUID.randomUUID().toString();
        command.review(reviewer.token(), approved(fresh, company, applicant.id(), false));
        command.assign(operator.token(), fresh);
    }

    @Test void conflictingClaimsAndRepeatsCannotReplaceOwnerOrMembership() throws Exception {
        Account applicant = account(false), other = account(false), reviewer = account(true), operator = account(true);
        long company = legacy(false), otherCompany = legacy(false);
        String reference = UUID.randomUUID().toString();
        var review = approved(reference, company, applicant.id(), false);
        command.review(reviewer.token(), review);
        assertThatThrownBy(() -> command.review(reviewer.token(), review)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command.review(reviewer.token(), approved(UUID.randomUUID().toString(), company, other.id(), false)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("conflicting");
        command.assign(operator.token(), reference);
        assertThatThrownBy(() -> command.assign(operator.token(), reference)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already owned");
        assertThatThrownBy(() -> command.review(reviewer.token(), approved(UUID.randomUUID().toString(), company, other.id(), false)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transfer prohibited");
        assertThatThrownBy(() -> command.review(reviewer.token(), approved(UUID.randomUUID().toString(), otherCompany, applicant.id(), false)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("active Company Membership");
        http.perform(get("/api/companies/me").header("Authorization", "Bearer "+applicant.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.companyId").value(company));
        http.perform(get("/api/auth/me").header("Authorization", "Bearer "+other.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeMembership").isEmpty());
    }

    @Test void concurrentOperatorsCommitOneAssignmentAndOneAuditFact() throws Exception {
        Account applicant = account(false), reviewer = account(true), first = account(true), second = account(true);
        long company = legacy(false);
        String reference = UUID.randomUUID().toString();
        command.review(reviewer.token(), approved(reference, company, applicant.id(), false));
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> race(first.token(), reference, ready, start));
            var b = pool.submit(() -> race(second.token(), reference, ready, start));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.List.of(a.get(10, java.util.concurrent.TimeUnit.SECONDS), b.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        var audit = command.inspect(first.token(), reference);
        assertThat(audit.operatorUserId()).isIn(first.id(), second.id());
        http.perform(get("/api/auth/me").header("Authorization", "Bearer "+applicant.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeMembership.companyId").value(company));
    }

    @Test void concurrentAssignmentsAcrossCompaniesCannotGiveApplicantTwoActiveMemberships() throws Exception {
        Account applicant = account(false), reviewer = account(true), first = account(true), second = account(true);
        long firstCompany = legacy(false), secondCompany = legacy(false);
        String firstRef = UUID.randomUUID().toString(), secondRef = UUID.randomUUID().toString();
        command.review(reviewer.token(), approved(firstRef, firstCompany, applicant.id(), false));
        command.review(reviewer.token(), approved(secondRef, secondCompany, applicant.id(), false));
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> race(first.token(), firstRef, ready, start));
            var b = pool.submit(() -> race(second.token(), secondRef, ready, start));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            boolean firstWon = a.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(b.get(10, java.util.concurrent.TimeUnit.SECONDS)).isNotEqualTo(firstWon);
            long winner = firstWon ? firstCompany : secondCompany;
            long loser = firstWon ? secondCompany : firstCompany;
            http.perform(get("/api/auth/me").header("Authorization", "Bearer "+applicant.token()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeMembership.companyId").value(winner));
            http.perform(get("/api/companies/"+loser).header("Authorization", "Bearer "+applicant.token())).andExpect(status().isNotFound());
            assertThatThrownBy(() -> command.inspect(first.token(), firstWon ? secondRef : firstRef)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void auditFailureRollsBackAssignmentAndTheSameApprovedCaseCanSafelyRetry() throws Exception {
        Account applicant = account(false), reviewer = account(true), operator = account(true);
        long company = legacy(false);
        String reference = UUID.randomUUID().toString();
        command.review(reviewer.token(), approved(reference, company, applicant.id(), false));
        database.execute("CREATE FUNCTION gw003_reject_audit() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'controlled audit failure' USING ERRCODE='23514'; END; $$ LANGUAGE plpgsql");
        database.execute("CREATE TRIGGER gw003_reject_audit BEFORE INSERT ON company_membership_audit_events FOR EACH ROW WHEN (NEW.actor_user_id = " + operator.id() + ") EXECUTE FUNCTION gw003_reject_audit()");
        try {
            assertThatThrownBy(() -> command.assign(operator.token(), reference)).isInstanceOf(RuntimeException.class);
            http.perform(get("/api/auth/me").header("Authorization", "Bearer "+applicant.token()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeMembership").isEmpty());
            assertThatThrownBy(() -> command.inspect(operator.token(), reference)).isInstanceOf(IllegalArgumentException.class);
        } finally {
            database.execute("DROP TRIGGER gw003_reject_audit ON company_membership_audit_events");
            database.execute("DROP FUNCTION gw003_reject_audit()");
        }
        command.assign(operator.token(), reference);
        assertThat(command.inspect(operator.token(), reference).outcome()).isEqualTo("ASSIGNED");
    }

    boolean race(String token, String reference, java.util.concurrent.CountDownLatch ready,
                 java.util.concurrent.CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Assignment race did not start");
        try { command.assign(token, reference); return true; }
        catch (IllegalArgumentException refused) { return false; }
    }

    @Test void omittedConflictReviewAcknowledgementCannotAuthorizeAssignment() throws Exception {
        Account applicant = account(false), reviewer = account(true);
        long company = legacy(false);
        var incomplete = new LegacyOwnershipCommand.Review(UUID.randomUUID().toString(), company, applicant.id(), 7L,
                "REJECTED", false, "LEGAL_REPRESENTATIVE_REGISTRATION", true, true, true, null, null);
        assertThatThrownBy(() -> command.review(reviewer.token(), incomplete)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicting claims");
    }

    @Test void documentedLocalCommandReviewsAssignsAndInspectsWithoutLoggingCredentials() throws Exception {
        Account applicant = account(false), reviewer = account(true), operator = account(true);
        long company = legacy(false);
        String reference = UUID.randomUUID().toString();
        var reviewInput = java.nio.file.Files.createTempFile("gw003-review-", ".json");
        var assignmentInput = java.nio.file.Files.createTempFile("gw003-assign-", ".json");
        try {
            java.nio.file.Files.setPosixFilePermissions(reviewInput, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            java.nio.file.Files.setPosixFilePermissions(assignmentInput, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            java.nio.file.Files.writeString(reviewInput, mapper.writeValueAsString(approved(reference, company, applicant.id(), false)));
            java.nio.file.Files.writeString(assignmentInput, mapper.writeValueAsString(Map.of("caseReference", reference)));
            assertThat(cli("review", reviewInput, reviewer.token())).contains("APPROVED", reference);
            assertThat(cli("assign", assignmentInput, operator.token())).contains("ASSIGNED", reference);
            assertThat(cli("inspect", assignmentInput, operator.token())).contains("reviewedAt", "assignedAt", "LEGAL_REPRESENTATIVE_REGISTRATION");
            http.perform(get("/api/companies/me").header("Authorization", "Bearer "+applicant.token()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.companyId").value(company));
        } finally {
            java.nio.file.Files.deleteIfExists(reviewInput);
            java.nio.file.Files.deleteIfExists(assignmentInput);
        }
    }

    String cli(String action, java.nio.file.Path input, String token) throws Exception {
        var output = java.nio.file.Files.createTempFile("gw003-command-output-", ".log");
        Process process = null;
        try {
            var builder = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    "com.guardwork.backend.BackendApplication", "--spring.profiles.active=test",
                    "--guardwork.ownership-command="+action, "--guardwork.ownership-input="+input);
            builder.environment().put("GUARDWORK_OWNERSHIP_TOKEN", token);
            builder.redirectErrorStream(true).redirectOutput(output.toFile());
            process = builder.start();
            assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String result = java.nio.file.Files.readString(output);
            assertThat(result).doesNotContain(token);
            assertThat(process.exitValue()).withFailMessage(result).isZero();
            return result;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            java.nio.file.Files.deleteIfExists(output);
        }
    }

    @Test void authorityExpiringWhileAssignmentWaitsForCompanyLockCannotAuthorizeTheCommit() throws Exception {
        Account applicant = account(false), reviewer = account(true), operator = account(true);
        long company = legacy(false);
        String reference = UUID.randomUUID().toString();
        var expiry = java.time.Instant.now().plusSeconds(2);
        command.review(reviewer.token(), new LegacyOwnershipCommand.Review(reference, company, applicant.id(), 7L, "REJECTED", false,
                "LEGAL_REPRESENTATIVE_AUTHORIZATION", true, true, true, false, expiry.toString()));
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var future = new org.springframework.transaction.support.TransactionTemplate(transactions).execute(tx -> {
                database.queryForObject("SELECT id FROM companies WHERE id=? FOR UPDATE", Long.class, company);
                var pending = pool.submit(() -> command.assign(operator.token(), reference));
                // Let the assignment reach its Company guard while current authority still exists.
                assertThatThrownBy(() -> pending.get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                while (java.time.Instant.now().isBefore(expiry)) {
                    try { Thread.sleep(20); } catch (InterruptedException e) { throw new RuntimeException(e); }
                }
                return pending;
            });
            assertThatThrownBy(() -> future.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> command.inspect(operator.token(), reference)).isInstanceOf(IllegalArgumentException.class);
        http.perform(get("/api/auth/me").header("Authorization", "Bearer "+applicant.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeMembership").isEmpty());
    }

    LegacyOwnershipCommand.Review approved(String ref, long company, long applicant, boolean banned) {
        return new LegacyOwnershipCommand.Review(ref, company, applicant, 7L, "REJECTED", banned,
                "LEGAL_REPRESENTATIVE_REGISTRATION", true, true, true, false, "2099-12-31T00:00:00Z");
    }
    long legacy(boolean banned) {
        return database.queryForObject("INSERT INTO companies(name,tax_code,email,registration_certificate_url,verification_status,rejection_reason,version,is_banned) VALUES ('Công ty Di Sản',?,?,'https://private.example.vn/license','REJECTED','Bổ sung giấy phép',7,?) RETURNING id",
                Long.class, UUID.randomUUID().toString(), UUID.randomUUID()+"@company.vn", banned);
    }
    Account account(boolean admin) throws Exception {
        String name = "legacy_" + UUID.randomUUID().toString().replace("-", "");
        String email = name + "@example.vn";
        http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                "firstName", "Ánh", "lastName", "Nguyễn", "username", name, "email", email, "password", "Password123"))))
                .andExpect(status().isCreated());
        // These fixtures represent existing legacy Users; new-User mailbox gates have their own HTTP suite.
        database.update("UPDATE users SET email_verified=true WHERE username=?", name);
        if (admin) database.update("UPDATE users SET role='STAFF' WHERE username=?", name);
        String body = http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("usernameOrEmail", name, "password", "Password123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(body).path("data").path("accessToken").asText();
        String me = http.perform(get("/api/auth/me").header("Authorization", "Bearer "+token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Account(mapper.readTree(me).path("data").path("id").asLong(), token);
    }
}
