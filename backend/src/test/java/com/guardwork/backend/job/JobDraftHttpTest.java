package com.guardwork.backend.job;

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
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "company.membership.notifications.worker-enabled=false")
@ActiveProfiles("test")
class JobDraftHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate database;
    MockMvc http;
    static final String JOBS = "/api/companies/me/jobs";
    @BeforeEach void setup() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test void ownerCreatesIncompletePrivateDraftBeforeCompanyVerification() throws Exception {
        String owner = account();
        createdCompany(owner);
        var draft = mapper.readTree(http.perform(post(JOBS).header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":{\"title\":\"  Bảo vệ\",\"description\":\"Dòng một\\nDòng hai  \"}}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.ownerResponsible").value(true))
                .andExpect(jsonPath("$.data.needsReassignment").value(false))
                .andExpect(jsonPath("$.data.workingRevision.content.description").value("Dòng một\nDòng hai"))
                .andReturn().getResponse().getContentAsString()).path("data");
        http.perform(get(JOBS + "/" + draft.path("id").asLong()).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.workingRevision.content.title").value("Bảo vệ"));
        http.perform(get(JOBS).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }
    @Test void savesReplaceContentAndRejectStaleOrMissingVersions() throws Exception {
        String owner = account(); createdCompany(owner);
        var initial = create(owner, Map.of("title", "Cũ", "description", "Previous"));
        long id = initial.path("id").asLong();
        var saved = mapper.readTree(http.perform(put(JOBS + "/" + id + "/draft").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content(saveBody(initial, Map.of("title", "Mới"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.workingRevision.content.title").value("Mới"))
                .andExpect(jsonPath("$.data.workingRevision.content.description").doesNotExist())
                .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(saved.path("version").asLong()).isGreaterThan(initial.path("version").asLong());
        assertThat(saved.path("workingRevision").path("version").asLong()).isGreaterThan(initial.path("workingRevision").path("version").asLong());
        http.perform(put(JOBS + "/" + id + "/draft").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content(saveBody(initial, Map.of("title", "Stale"))))
                .andExpect(status().isConflict());
        http.perform(put(JOBS + "/" + id + "/draft").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":{}}"))
                .andExpect(status().isBadRequest());
        http.perform(get(JOBS + "/" + id).header("Authorization", owner))
                .andExpect(jsonPath("$.data.workingRevision.content.title").value("Mới"));
    }

    @Test void recruiterCreationAssignsCurrentPeriodAndConcealsEveryOtherJob() throws Exception {
        String owner = account(), recruiter = account(), stranger = account(), otherOwner = account();
        long company = createdCompany(owner); createdCompany(otherOwner);
        long member = recruiter(company, recruiter);
        var privateOwnerJob = create(owner, Map.of());
        var assigned = create(recruiter, Map.of("title", "Tuyển bảo vệ"));
        long id = assigned.path("id").asLong();
        assertThat(assigned.path("ownerResponsible").asBoolean()).isFalse();
        assertThat(assigned.path("needsReassignment").asBoolean()).isFalse();
        assertThat(assigned.path("assignments").get(0).path("membershipId").asLong()).isEqualTo(member);
        http.perform(get(JOBS).header("Authorization", owner)).andExpect(jsonPath("$.data.totalElements").value(2));
        http.perform(get(JOBS).header("Authorization", recruiter)).andExpect(jsonPath("$.data.totalElements").value(1));
        for (String actor : new String[] {recruiter, stranger, otherOwner}) {
            String absent = http.perform(get(JOBS + "/" + Long.MAX_VALUE).header("Authorization", actor))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            http.perform(get(JOBS + "/" + privateOwnerJob.path("id").asLong()).header("Authorization", actor))
                    .andExpect(status().isNotFound()).andExpect(content().json(absent));
            http.perform(put(JOBS + "/" + privateOwnerJob.path("id").asLong() + "/draft").header("Authorization", actor)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound()).andExpect(content().json(absent));
        }
        http.perform(get("/api/me/notifications").header("Authorization", recruiter))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].eventType").value("JOB_ASSIGNED"))
                .andExpect(jsonPath("$.data.content[0].jobId").value(id));
        http.perform(post("/api/companies/me/membership/leave").header("Authorization", recruiter))
                .andExpect(status().isOk());
        http.perform(get(JOBS + "/" + id).header("Authorization", recruiter)).andExpect(status().isNotFound());
        // Simulates a persistence path reusing the membership row for a new period.
        database.update("UPDATE company_memberships SET status='ACTIVE', ended_at=null, ended_by_user_id=null, joined_at=joined_at+interval '1 second' WHERE id=?", member);
        http.perform(get(JOBS).header("Authorization", recruiter)).andExpect(jsonPath("$.data.totalElements").value(0));
        http.perform(put(JOBS + "/" + id + "/draft").header("Authorization", recruiter)
                .contentType(MediaType.APPLICATION_JSON).content(saveBody(assigned, Map.of("title", "Forbidden"))))
                .andExpect(status().isNotFound());
        http.perform(get(JOBS + "?needsReassignment=true").header("Authorization", owner))
                .andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.content[0].id").value(id));
    }

    @Test void draftContentBoundsAreStructuralAndListsStayScoped() throws Exception {
        String owner = account(); createdCompany(owner);
        create(owner, Map.of("title", "😀".repeat(200)));
        create(owner, Map.of("title", "Mới"));
        for (Map<String, Object> invalid : java.util.List.of(
                Map.<String, Object>of("title", "😀".repeat(201)), Map.<String, Object>of("unexpected", true),
                Map.<String, Object>of("headcount", 0), Map.<String, Object>of("headcount", 1.5),
                Map.<String, Object>of("headcount", 10001), Map.<String, Object>of("employmentType", "INVALID"),
                Map.<String, Object>of("workArrangement", "INVALID"), Map.<String, Object>of("title", 3),
                Map.<String, Object>of("deadline", "2026-12-01T12:00:00"),
                Map.<String, Object>of("compensation", Map.of("minimum", -1)),
                Map.<String, Object>of("compensation", Map.of("minimum", 1.001)),
                Map.<String, Object>of("compensation", Map.of("currency", "EUR")),
                Map.<String, Object>of("compensation", Map.of("unknown", true)),
                Map.<String, Object>of("screeningQuestions", java.util.Collections.nCopies(11, Map.of())),
                Map.<String, Object>of("screeningQuestions", java.util.List.of(Map.of("key", "not-a-uuid"))),
                Map.<String, Object>of("screeningQuestions", java.util.List.of(Map.of("options", java.util.List.of("a", " a ")))))) {
            http.perform(post(JOBS).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("content", invalid))))
                    .andExpect(status().isBadRequest());
        }
        http.perform(post(JOBS).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":{},\"padding\":\"" + "x".repeat(262144) + "\"}"))
                .andExpect(status().isBadRequest());
        for (String query : new String[] {"?page=-1", "?size=0", "?size=101", "?page=2147483647&size=100", "?status=UNKNOWN", "?needsReassignment=bad"})
            http.perform(get(JOBS + query).header("Authorization", owner)).andExpect(status().isBadRequest());
        http.perform(get(JOBS + "?size=1&status=DRAFT&needsReassignment=false").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].workingRevision.content.title").value("Mới"));
        http.perform(get(JOBS + "?page=1&size=1").header("Authorization", owner))
                .andExpect(jsonPath("$.data.content[0].workingRevision.content.title").value("😀".repeat(200)));
    }

    @Test void personalProofIsPreservedWhileVerifiedMembersCanDraftBeforeCompanyApproval() throws Exception {
        String owner = account(); long company = createdCompany(owner); long user = userId(owner);
        database.update("INSERT INTO employer_profiles(user_id,applicant,full_name) VALUES (?,true,'Owner')", user);
        http.perform(post(JOBS).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content("{\"content\":{}}"))
                .andExpect(status().isForbidden());
        database.update("UPDATE employer_profiles SET email_verified_at=now(),phone=?,phone_verified_at=now() WHERE user_id=?", "09" + user, user);
        create(owner, Map.of());
        database.update("UPDATE companies SET verification_status='REJECTED' WHERE id=?", company);
        create(owner, Map.of());
        String recipient = account(); long member = recruiter(company, recipient);
        create(recipient, Map.of()); // Legacy membership stays isolated; drafting never auto-enrolls it.
        assertThat(database.queryForObject("SELECT count(*) FROM employer_profiles WHERE user_id=?", Long.class, userId(recipient))).isZero();
        database.update("UPDATE company_memberships SET role='ADMIN' WHERE id=?", member);
        http.perform(get(JOBS).header("Authorization", recipient)).andExpect(status().isForbidden());
        database.update("UPDATE companies SET is_banned=true WHERE id=?", company);
        http.perform(get(JOBS).header("Authorization", owner)).andExpect(status().isForbidden());
        database.update("UPDATE companies SET is_banned=false WHERE id=?", company);
        database.update("UPDATE users SET status='SUSPENDED' WHERE id=?", user);
        http.perform(post(JOBS).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content("{\"content\":{}}"))
                .andExpect(status().isForbidden());
    }

    @Autowired com.guardwork.backend.notification.service.NotificationDispatcher dispatcher;

    @Test void notificationIntentFailureRollsBackJobRevisionAssignmentAndAudit() throws Exception {
        String owner = account(), recruiter = account(); long company = createdCompany(owner);
        recruiter(company, recruiter); long recipient = userId(recruiter);
        database.execute("CREATE FUNCTION gw78_fail_intent() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'controlled failure' USING ERRCODE='23514'; END; $$ LANGUAGE plpgsql");
        database.execute("CREATE TRIGGER gw78_fail_intent BEFORE INSERT ON notification_outbox FOR EACH ROW WHEN (NEW.recipient_user_id=" + recipient + " AND NEW.event_type='JOB_ASSIGNED') EXECUTE FUNCTION gw78_fail_intent()");
        try {
            http.perform(post(JOBS).header("Authorization", recruiter).contentType(MediaType.APPLICATION_JSON).content("{\"content\":{}}"))
                    .andExpect(status().isInternalServerError());
            http.perform(get(JOBS).header("Authorization", owner)).andExpect(jsonPath("$.data.totalElements").value(0));
            for (String table : new String[] {"job_postings", "job_assignments", "job_audit_events", "notification_outbox"})
                assertThat(database.queryForObject("SELECT count(*) FROM " + table + " WHERE company_id=?", Long.class, company)).isZero();
            assertThat(database.queryForObject("SELECT count(*) FROM job_posting_revisions r JOIN job_postings j ON j.id=r.job_id WHERE j.company_id=?", Long.class, company)).isZero();
        } finally {
            database.execute("DROP TRIGGER gw78_fail_intent ON notification_outbox");
            database.execute("DROP FUNCTION gw78_fail_intent()");
        }
        create(recruiter, Map.of());
    }

    @Test void deliveryFailureKeepsCreationAndRecoveryDeduplicatesRestrictedRecipientInbox() throws Exception {
        String owner = account(), recruiter = account(); long company = createdCompany(owner);
        recruiter(company, recruiter); long recipient = userId(recruiter);
        database.execute("CREATE FUNCTION gw78_fail_inbox() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'controlled failure' USING ERRCODE='23514'; END; $$ LANGUAGE plpgsql");
        database.execute("CREATE TRIGGER gw78_fail_inbox BEFORE INSERT ON user_notifications FOR EACH ROW WHEN (NEW.recipient_user_id=" + recipient + ") EXECUTE FUNCTION gw78_fail_inbox()");
        long job;
        try {
            job = create(recruiter, Map.of()).path("id").asLong();
            http.perform(get(JOBS + "/" + job).header("Authorization", recruiter)).andExpect(status().isOk());
            http.perform(get("/api/me/notifications").header("Authorization", recruiter)).andExpect(jsonPath("$.data.totalElements").value(0));
        } finally {
            database.execute("DROP TRIGGER gw78_fail_inbox ON user_notifications");
            database.execute("DROP FUNCTION gw78_fail_inbox()");
        }
        UUID event = database.queryForObject("SELECT event_id FROM notification_outbox WHERE recipient_user_id=? AND event_type='JOB_ASSIGNED'", UUID.class, recipient);
        dispatcher.replay(event); dispatcher.replay(event);
        database.update("INSERT INTO employer_profiles(user_id,applicant,full_name) VALUES (?,true,'Recruiter')", recipient);
        var item = mapper.readTree(http.perform(get("/api/me/notifications").header("Authorization", recruiter))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].jobId").value(job))
                .andReturn().getResponse().getContentAsString()).path("data").path("content").get(0);
        long notification = item.path("id").asLong();
        http.perform(patch("/api/me/notifications/" + notification + "/read").header("Authorization", recruiter))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.readAt").isNotEmpty());
        http.perform(patch("/api/me/notifications/" + notification + "/read").header("Authorization", owner))
                .andExpect(status().isNotFound());
        http.perform(get("/api/me/notifications").header("Authorization", owner)).andExpect(jsonPath("$.data.totalElements").value(0));
        http.perform(get(JOBS + "/" + job).header("Authorization", recruiter)).andExpect(status().isForbidden());
        database.update("UPDATE users SET status='SUSPENDED' WHERE id=?", recipient);
        http.perform(get("/api/me/notifications").header("Authorization", recruiter)).andExpect(status().isForbidden());
    }

    @Test void concurrentSavesHaveOneWinnerWithoutOverwritingItsContent() throws Exception {
        String owner = account(), recruiter = account(); long company = createdCompany(owner);
        recruiter(company, recruiter); var initial = create(recruiter, Map.of("title", "Original"));
        long job = initial.path("id").asLong();
        try (var connection = database.getDataSource().getConnection();
                var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            try (var guard = connection.prepareStatement("SELECT id FROM companies WHERE id=? FOR UPDATE")) {
                guard.setLong(1, company); guard.executeQuery().close();
            }
            String firstBody = saveBody(initial, Map.of("title", "Owner edit"));
            String secondBody = saveBody(initial, Map.of("title", "Recruiter edit"));
            var first = workers.submit(() -> http.perform(put(JOBS + "/" + job + "/draft").header("Authorization", owner)
                    .contentType(MediaType.APPLICATION_JSON).content(firstBody)).andReturn().getResponse().getStatus());
            var second = workers.submit(() -> http.perform(put(JOBS + "/" + job + "/draft").header("Authorization", recruiter)
                    .contentType(MediaType.APPLICATION_JSON).content(secondBody)).andReturn().getResponse().getStatus());
            try { awaitDatabaseLocks(2); connection.commit(); } finally { connection.rollback(); }
            assertThat(java.util.List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        var current = mapper.readTree(http.perform(get(JOBS + "/" + job).header("Authorization", owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(current.path("workingRevision").path("content").path("title").asText()).isIn("Owner edit", "Recruiter edit");
        http.perform(put(JOBS + "/" + job + "/draft").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content(saveBody(current, Map.of("title", "Next edit"))))
                .andExpect(status().isOk());
    }

    @Test void revokedAssignmentCommittedWhileSaveWaitsPreventsContentAndAuditChanges() throws Exception {
        String owner = account(), recruiter = account(); long company = createdCompany(owner);
        recruiter(company, recruiter); var initial = create(recruiter, Map.of("title", "Original"));
        long job = initial.path("id").asLong();
        long audits = database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=?", Long.class, job);
        try (var connection = database.getDataSource().getConnection();
                var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var guard = connection.prepareStatement("SELECT id FROM companies WHERE id=? FOR UPDATE")) {
                guard.setLong(1, company); guard.executeQuery().close();
            }
            try (var revoke = connection.prepareStatement("UPDATE job_assignments SET ended_at=now(), ended_by_user_id=?, end_reason='UNASSIGNED' WHERE job_id=?")) {
                revoke.setLong(1, userId(owner)); revoke.setLong(2, job); revoke.executeUpdate();
            }
            String body = saveBody(initial, Map.of("title", "Forbidden edit"));
            var saving = worker.submit(() -> http.perform(put(JOBS + "/" + job + "/draft").header("Authorization", recruiter)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
            try { awaitDatabaseLocks(1); connection.commit(); } finally { connection.rollback(); }
            assertThat(saving.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(404);
        }
        http.perform(get(JOBS + "/" + job).header("Authorization", owner))
                .andExpect(jsonPath("$.data.workingRevision.content.title").value("Original"));
        assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=?", Long.class, job)).isEqualTo(audits);
    }

    void awaitDatabaseLocks(int count) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Long waiting = database.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'", Long.class);
            if (waiting >= count) return;
            Thread.sleep(20);
        }
        throw new AssertionError("HTTP writes did not reach held database locks");
    }

    @Test void compensationDecimalsRemainExactAcrossHttpPersistenceAndLaterSaves() throws Exception {
        String owner = account(); createdCompany(owner);
        String body = "{\"content\":{\"compensation\":{\"minimum\":9999999999999999.99}}}";
        var response = http.perform(post(JOBS).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("9999999999999999.99");
        long id = mapper.readTree(response).path("data").path("id").asLong();
        var read = http.perform(get(JOBS + "/" + id).header("Authorization", owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(read).contains("9999999999999999.99");
    }

    long recruiter(long company, String token) throws Exception {
        return database.queryForObject("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?,?,'RECRUITER','ACTIVE',now()) RETURNING id",
                Long.class, company, userId(token));
    }

    com.fasterxml.jackson.databind.JsonNode create(String actor, Map<String, Object> content) throws Exception {
        return mapper.readTree(http.perform(post(JOBS).header("Authorization", actor)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
    }
    String saveBody(com.fasterxml.jackson.databind.JsonNode detail, Map<String, Object> content) throws Exception {
        return mapper.writeValueAsString(Map.of("expectedJobVersion", detail.path("version").asLong(),
                "expectedRevisionVersion", detail.path("workingRevision").path("version").asLong(), "content", content));
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
