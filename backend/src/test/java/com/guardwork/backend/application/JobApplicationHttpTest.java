package com.guardwork.backend.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.candidate.cvs.CandidateCvStorage;
import com.guardwork.backend.common.exception.AppException;
import com.guardwork.backend.common.exception.ErrorCode;
import com.guardwork.backend.user.AccountPhone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "company.membership.notifications.worker-enabled=false",
        "jobs.expiry.worker-enabled=false"
})
@ActiveProfiles("test")
@Import(JobApplicationHttpTest.StorageConfig.class)
class JobApplicationHttpTest {

    private static final String APPLICATIONS = "/api/candidate/applications";
    private static final String PDF = "application/pdf";

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate database;
    @Autowired FakeCvStorage storage;
    @Autowired PlatformTransactionManager transactions;

    MockMvc http;

    @BeforeEach
    void setUp() {
        storage.reset();
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void formPrefillsAndSubmitStoresAnImmutableSnapshot() throws Exception {
        String candidate = account();
        long userId = userId(candidate);
        UUID profile = profile(userId);
        String accountPhone = AccountPhone.normalize(phone());
        database.update("UPDATE users SET phone_number=? WHERE id=?", accountPhone, userId);
        UUID cv = cv(profile);
        long company = company();
        Job job = publishedJob(company, userId);

        JsonNode submitted = mapper.readTree(http.perform(post(APPLICATIONS)
                        .header("Authorization", candidate)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(job.id(), cv, "  Nguyễn Văn An  ", "  Em quan tâm  ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.jobId").value(job.id()))
                .andReturn().getResponse().getContentAsString()).path("data");

        UUID application = UUID.fromString(submitted.path("id").asText());
        assertThat(database.queryForObject(
                "SELECT count(*) FROM applications WHERE id=? AND candidate_id=? AND job_id=?",
                Long.class, application, profile, job.id())).isEqualTo(1);
        Map<String, Object> snapshot = database.queryForMap(
                "SELECT * FROM application_snapshots WHERE application_id=?", application);
        assertThat(snapshot.get("full_name")).isEqualTo("Nguyễn Văn An");
        assertThat(snapshot.get("email")).isEqualTo("an@example.vn");
        assertThat(snapshot.get("phone_number")).isEqualTo("0901234567");
        assertThat(snapshot.get("cover_letter")).isEqualTo("Em quan tâm");
        assertThat(snapshot.get("job_title")).isEqualTo("Backend Engineer");
        assertThat(snapshot.get("company_name")).isEqualTo("Công ty Ánh");
        assertThat(snapshot.get("source_cv_id")).isEqualTo(cv);
        String copiedKey = (String) snapshot.get("cv_storage_key");
        assertThat(copiedKey).startsWith("applications/" + application + "/cv/");
        assertThat(storage.has(copiedKey)).isTrue();

        // The snapshot survives deletion of the candidate's library CV and its Cloudinary object.
        database.update("DELETE FROM candidate_cvs WHERE id=?", cv);
        storage.remove("candidates/" + profile + "/cvs/source");
        assertThat(database.queryForObject(
                "SELECT count(*) FROM application_snapshots WHERE application_id=?", Long.class, application))
                .isEqualTo(1);
        assertThat(storage.has(copiedKey)).isTrue();

        http.perform(post(APPLICATIONS)
                        .header("Authorization", candidate)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(job.id(), cv, "Nguyễn Văn An", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(ErrorCode.APPLICATION_ALREADY_SUBMITTED.getCode()));
        assertThat(database.queryForObject(
                "SELECT count(*) FROM applications WHERE candidate_id=? AND job_id=?",
                Long.class, profile, job.id())).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
            "RECRUITER,ACTIVE,true,true", "ADMIN,ACTIVE,true,true", "OWNER,ACTIVE,true,true",
            "RECRUITER,ACTIVE,false,false", "ADMIN,ACTIVE,false,false", "OWNER,ACTIVE,false,false",
            "RECRUITER,LEFT,true,false", "RECRUITER,REMOVED,true,false"
    })
    void submissionUsesCurrentCompanyMembership(String role, String membershipStatus,
                                               boolean sameCompany, boolean forbidden) throws Exception {
        // Issue the token before membership exists: eligibility must use current persistence.
        String candidate = account();
        long user = userId(candidate);
        UUID candidateProfile = profile(user);
        UUID candidateCv = cv(candidateProfile);
        long employer = company();
        long membershipCompany = sameCompany ? employer : company();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            if ("OWNER".equals(role)) {
                database.update("UPDATE companies SET owner_user_id=? WHERE id=?", user, membershipCompany);
            }
            database.update("""
                    INSERT INTO company_memberships(
                        company_id,user_id,role,status,joined_at,ended_at,ended_by_user_id)
                    VALUES (?,?,?,?,now(),CASE WHEN ?='ACTIVE' THEN NULL ELSE now() END,
                        CASE WHEN ?='ACTIVE' THEN NULL ELSE ? END)
                    """, membershipCompany, user, role, membershipStatus, membershipStatus, membershipStatus, user);
        });
        Job job = publishedJob(employer, user);

        var result = http.perform(post(APPLICATIONS)
                .header("Authorization", candidate)
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody(job.id(), candidateCv, "Nguyễn Văn An", null)));

        if (forbidden) {
            result.andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(ErrorCode.FORBIDDEN.getCode()));
            assertThat(database.queryForObject(
                    "SELECT count(*) FROM applications WHERE candidate_id=?", Long.class, candidateProfile))
                    .isZero();
            assertThat(storage.assets.keySet()).containsExactly("candidates/" + candidateProfile + "/cvs/source");
        } else {
            result.andExpect(status().isCreated());
        }
    }

    @Test
    void candidateCannotAttachAnotherCandidatesCv() throws Exception {
        String candidate = account();
        long userId = userId(candidate);
        UUID profile = profile(userId);
        long company = company();
        Job job = publishedJob(company, userId);

        String other = account();
        UUID otherProfile = profile(userId(other));
        UUID otherCv = cv(otherProfile);

        http.perform(post(APPLICATIONS)
                        .header("Authorization", candidate)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(job.id(), otherCv, "Nguyễn Văn An", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(ErrorCode.CV_NOT_FOUND.getCode()));

        assertThat(database.queryForObject(
                "SELECT count(*) FROM applications WHERE candidate_id=?", Long.class, profile)).isZero();
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        http.perform(post(APPLICATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(1L, UUID.randomUUID(), "Nguyễn Văn An", null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void suspendedCandidateIsForbiddenBeforeAnyJobWork() throws Exception {
        String candidate = account();
        long userId = userId(candidate);
        profile(userId);
        database.update("UPDATE users SET status='SUSPENDED' WHERE id=?", userId);

        http.perform(post(APPLICATIONS)
                        .header("Authorization", candidate)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(999_999L, UUID.randomUUID(), "Nguyễn Văn An", null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void copyFailureLeavesNoApplicationOrSnapshot() throws Exception {
        String candidate = account();
        long userId = userId(candidate);
        UUID profile = profile(userId);
        UUID cv = cv(profile);
        Job job = publishedJob(company(), userId);
        storage.failCopy();

        http.perform(post(APPLICATIONS)
                        .header("Authorization", candidate)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(job.id(), cv, "Nguyễn Văn An", null)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(ErrorCode.APPLICATION_CV_COPY_FAILED.getCode()));

        assertThat(database.queryForObject(
                "SELECT count(*) FROM applications WHERE candidate_id=?", Long.class, profile)).isZero();
        assertThat(database.queryForObject("""
                SELECT count(*) FROM application_snapshots s
                JOIN applications a ON a.id=s.application_id
                WHERE a.candidate_id=?
                """, Long.class, profile)).isZero();
    }

    @Test
    void unavailableJobIsRejectedWithConflict() throws Exception {
        String candidate = account();
        long userId = userId(candidate);
        UUID profile = profile(userId);
        UUID cv = cv(profile);
        Job job = publishedJob(company(), userId);
        database.update("UPDATE job_postings SET status='PAUSED' WHERE id=?", job.id());

        http.perform(post(APPLICATIONS)
                        .header("Authorization", candidate)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(job.id(), cv, "Nguyễn Văn An", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(ErrorCode.JOB_NOT_ACCEPTING_APPLICATIONS.getCode()));

        assertThat(database.queryForObject(
                "SELECT count(*) FROM applications WHERE candidate_id=?", Long.class, profile)).isZero();
    }

    @Test
    void missingJobReturnsNotFound() throws Exception {
        String candidate = account();
        long userId = userId(candidate);
        profile(userId);

        http.perform(post(APPLICATIONS)
                        .header("Authorization", candidate)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(999_999L, UUID.randomUUID(), "Nguyễn Văn An", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(ErrorCode.JOB_NOT_FOUND.getCode()));
    }

    @Test
    void companyOwnerCanReadOnlyTheSubmittedApplicationSummary() throws Exception {
        String owner = account();
        long ownerId = userId(owner);
        database.update("UPDATE users SET email_verified=true WHERE id=?", ownerId);
        long company = company();
        addOwner(company, ownerId);
        Job job = publishedJob(company, ownerId);

        String candidate = account();
        long candidateId = userId(candidate);
        UUID candidateProfile = profile(candidateId);
        UUID applicationId = saveApplication(job, candidateProfile, "Nguyễn Văn An", "2026-10-31T12:00:00Z");
        database.update("UPDATE users SET first_name='Updated',last_name='Candidate' WHERE id=?", candidateId);
        Long auditBefore = database.queryForObject(
                "SELECT count(*) FROM job_audit_events WHERE job_id=?", Long.class, job.id());
        Long notificationsBefore = database.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE job_id=?", Long.class, job.id());

        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.pageNumber").value(0))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(applicationId.toString()))
                .andExpect(jsonPath("$.data.content[0].candidateName").value("Nguyễn Văn An"))
                .andExpect(jsonPath("$.data.content[0].status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.content[0].appliedAt").value("2026-10-31T12:00:00Z"))
                .andExpect(jsonPath("$.data.content[0].email").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].phoneNumber").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].coverLetter").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].cvFileName").doesNotExist());
        assertThat(database.queryForObject(
                "SELECT count(*) FROM job_audit_events WHERE job_id=?", Long.class, job.id())).isEqualTo(auditBefore);
        assertThat(database.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE job_id=?", Long.class, job.id()))
                .isEqualTo(notificationsBefore);
    }

    @Test
    void companyOwnerGetsStablePaginationAndEmptyPages() throws Exception {
        String owner = account();
        long ownerId = userId(owner);
        database.update("UPDATE users SET email_verified=true WHERE id=?", ownerId);
        long company = company();
        addOwner(company, ownerId);
        Job job = publishedJob(company, ownerId);
        String uuidPrefix = "%08x".formatted(ThreadLocalRandom.current().nextInt(1, 0x7fffffff));
        UUID lowerId = UUID.fromString(uuidPrefix + "-0000-0000-0000-000000000001");
        UUID higherId = UUID.fromString(uuidPrefix + "-0000-0000-0000-000000000002");
        String tiedAt = "2026-10-31T12:00:00Z";
        saveApplication(job, profile(userId(account())), "Older UUID", tiedAt, lowerId);
        saveApplication(job, profile(userId(account())), "Newer UUID", tiedAt, higherId);
        saveApplication(job, profile(userId(account())), "Earlier submission", "2026-10-30T12:00:00Z");

        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .queryParam("size", "1")
                        .header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(higherId.toString()))
                .andExpect(jsonPath("$.data.pageNumber").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.first").value(true))
                .andExpect(jsonPath("$.data.last").value(false));

        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .queryParam("size", "1").queryParam("page", "1")
                        .header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(lowerId.toString()));

        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .queryParam("size", "1").queryParam("page", "8")
                        .header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.last").value(true));

        for (String[] query : new String[][]{{"page", "-1"}, {"size", "0"}, {"size", "101"},
                {"page", "2147483647"}, {"page", "2147483647", "size", "100"}}) {
            var request = get("/api/companies/me/jobs/{jobId}/applications", job.id())
                    .header("Authorization", owner);
            for (int i = 0; i < query.length; i += 2) request.queryParam(query[i], query[i + 1]);
            http.perform(request).andExpect(status().isBadRequest());
        }
    }

    @Test
    void recruiterNeedsAnAssignmentInTheCurrentMembershipPeriod() throws Exception {
        String owner = account();
        long ownerId = userId(owner);
        database.update("UPDATE users SET email_verified=true WHERE id=?", ownerId);
        long company = company();
        addOwner(company, ownerId);
        Job job = publishedJob(company, ownerId);

        String recruiter = account();
        long recruiterId = userId(recruiter);
        database.update("UPDATE users SET email_verified=true WHERE id=?", recruiterId);
        long membership = addRecruiter(company, recruiterId);
        assign(job, company, membership, ownerId);
        saveApplication(job, profile(userId(account())), "Assigned Candidate", "2026-10-31T12:00:00Z");

        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .header("Authorization", recruiter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));

        database.update("UPDATE job_assignments SET ended_at=now(),ended_by_user_id=?,end_reason='REVOKED' " +
                "WHERE job_id=? AND membership_id=?", ownerId, job.id(), membership);
        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .header("Authorization", recruiter))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));

        assign(job, company, membership, ownerId);
        database.update("UPDATE company_memberships SET status='LEFT',ended_at=now(),ended_by_user_id=? " +
                "WHERE id=?", ownerId, membership);
        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .header("Authorization", recruiter))
                .andExpect(status().isForbidden());

        long nextMembershipPeriod = addRecruiter(company, recruiterId);
        assertThat(nextMembershipPeriod).isNotEqualTo(membership);
        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .header("Authorization", recruiter))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void suspendedJobIsConcealedFromOwnerAndAssignedRecruiter() throws Exception {
        String owner = account();
        long ownerId = userId(owner);
        database.update("UPDATE users SET email_verified=true WHERE id=?", ownerId);
        long company = company();
        addOwner(company, ownerId);
        Job job = publishedJob(company, ownerId);

        String recruiter = account();
        long recruiterId = userId(recruiter);
        database.update("UPDATE users SET email_verified=true WHERE id=?", recruiterId);
        long membership = addRecruiter(company, recruiterId);
        assign(job, company, membership, ownerId);
        saveApplication(job, profile(userId(account())), "Private Candidate", "2026-10-31T12:00:00Z");

        database.update("UPDATE job_postings SET status='PAUSED' WHERE id=?", job.id());
        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));

        Job closedJob = publishedJob(company, ownerId);
        database.update("UPDATE job_postings SET status='CLOSED',closed_at=now() WHERE id=?", closedJob.id());
        http.perform(get("/api/companies/me/jobs/{jobId}/applications", closedJob.id())
                        .header("Authorization", owner))
                .andExpect(status().isOk());

        Job expiredJob = publishedJob(company, ownerId);
        database.update("UPDATE job_postings SET status='EXPIRED',expired_at=now() WHERE id=?", expiredJob.id());
        http.perform(get("/api/companies/me/jobs/{jobId}/applications", expiredJob.id())
                        .header("Authorization", owner))
                .andExpect(status().isOk());

        database.update("UPDATE job_postings SET status='SUSPENDED',staff_suspended=true WHERE id=?", job.id());
        for (String token : new String[]{owner, recruiter}) {
            http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                            .header("Authorization", token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        }
    }

    @Test
    void unassignedRecruiterAdminAndOtherCompanyCannotDiscoverApplications() throws Exception {
        String owner = account();
        long ownerId = userId(owner);
        database.update("UPDATE users SET email_verified=true WHERE id=?", ownerId);
        long company = company();
        addOwner(company, ownerId);
        Job job = publishedJob(company, ownerId);
        saveApplication(job, profile(userId(account())), "Private Candidate", "2026-10-31T12:00:00Z");

        String recruiter = account();
        long recruiterId = userId(recruiter);
        database.update("UPDATE users SET email_verified=true WHERE id=?", recruiterId);
        addRecruiter(company, recruiterId);
        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .header("Authorization", recruiter))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));

        String admin = account();
        long adminId = userId(admin);
        database.update("UPDATE users SET email_verified=true WHERE id=?", adminId);
        database.update("""
                INSERT INTO company_memberships(company_id,user_id,role,status,joined_at)
                VALUES (?,?,'ADMIN','ACTIVE',now())
                """, company, adminId);
        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .header("Authorization", admin))
                .andExpect(status().isForbidden());

        String otherOwner = account();
        long otherOwnerId = userId(otherOwner);
        database.update("UPDATE users SET email_verified=true WHERE id=?", otherOwnerId);
        long otherCompany = company();
        addOwner(otherCompany, otherOwnerId);
        Job otherJob = publishedJob(otherCompany, otherOwnerId);
        saveApplication(otherJob, profile(userId(account())), "Other Company Candidate", "2026-10-31T12:00:00Z");
        http.perform(get("/api/companies/me/jobs/{jobId}/applications", otherJob.id())
                        .header("Authorization", owner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void listingRequiresVerifiedCompanyAndVerifiedActorEmail() throws Exception {
        String owner = account();
        long ownerId = userId(owner);
        database.update("UPDATE users SET email_verified=true WHERE id=?", ownerId);
        long company = company();
        addOwner(company, ownerId);
        Job job = publishedJob(company, ownerId);

        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id()))
                .andExpect(status().isUnauthorized());

        database.update("UPDATE companies SET verification_status='PENDING' WHERE id=?", company);
        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .header("Authorization", owner))
                .andExpect(status().isForbidden());

        database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?", company);
        database.update("UPDATE users SET email_verified=false WHERE id=?", ownerId);
        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .header("Authorization", owner))
                .andExpect(status().isForbidden());

        database.update("UPDATE users SET email_verified=true,status='SUSPENDED' WHERE id=?", ownerId);
        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .header("Authorization", owner))
                .andExpect(status().isUnauthorized());

        database.update("UPDATE users SET status='ACTIVE' WHERE id=?", ownerId);
        database.update("UPDATE companies SET is_banned=true WHERE id=?", company);
        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .header("Authorization", owner))
                .andExpect(status().isNotFound());
    }

    @Test
    void recruiterSearchFindsVietnameseSnapshotNamesWithoutChangingTheirDisplay() throws Exception {
        String owner = account();
        long ownerId = userId(owner);
        database.update("UPDATE users SET email_verified=true WHERE id=?", ownerId);
        long company = company();
        addOwner(company, ownerId);
        Job job = publishedJob(company, ownerId);
        saveApplication(job, profile(userId(account())), "Nguyễn Văn An", "2026-10-31T12:00:00Z");
        saveApplication(job, profile(userId(account())), "Trần Bình", "2026-10-30T12:00:00Z");

        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .queryParam("search", "  NGUYEN  ")
                        .header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].candidateName").value("Nguyễn Văn An"));
    }

    @Test
    void recruiterSearchTreatsWildcardsLiterallyAndPaginatesFilteredTotals() throws Exception {
        String owner = account();
        long ownerId = userId(owner);
        database.update("UPDATE users SET email_verified=true WHERE id=?", ownerId);
        long company = company();
        addOwner(company, ownerId);
        Job job = publishedJob(company, ownerId);
        saveApplication(job, profile(userId(account())), "Nguyễn Văn An", "2026-10-31T12:00:00Z");
        saveApplication(job, profile(userId(account())), "Nguyễn% Văn", "2026-10-30T12:00:00Z");
        saveApplication(job, profile(userId(account())), "Nguyễn_ Văn", "2026-10-29T12:00:00Z");
        UUID viewed = saveApplication(job, profile(userId(account())), "Nguyễn Đức Anh", "2026-10-28T12:00:00Z");
        database.update("UPDATE applications SET status='VIEWED' WHERE id=?", viewed);

        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .queryParam("search", "%").header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].candidateName").value("Nguyễn% Văn"));

        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .queryParam("search", "_").header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].candidateName").value("Nguyễn_ Văn"));

        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .queryParam("search", "   ").header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4));

        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .queryParam("search", " nguyen ").queryParam("status", "SUBMITTED")
                        .queryParam("size", "1").queryParam("page", "1")
                        .header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.content[0].status").value("SUBMITTED"));

        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .queryParam("search", "x".repeat(150)).header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                        .queryParam("search", "x".repeat(151)).header("Authorization", owner))
                .andExpect(status().isBadRequest());

        for (String statusFilter : new String[]{"VIEWED", "submitted", " SUBMITTED "}) {
            http.perform(get("/api/companies/me/jobs/{jobId}/applications", job.id())
                            .queryParam("status", statusFilter).header("Authorization", owner))
                    .andExpect(status().isBadRequest());
        }
    }

    static String phone() {
        return "09%08d".formatted(Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000_000));
    }

    String submitBody(long jobId, UUID cvId, String fullName, String coverLetter) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("jobId", jobId);
        body.put("fullName", fullName);
        body.put("email", "an@example.vn");
        body.put("phoneNumber", "0901234567");
        body.put("cvId", cvId);
        if (coverLetter != null) {
            body.put("coverLetter", coverLetter);
        }
        return mapper.writeValueAsString(body);
    }

    String account() throws Exception {
        String name = "app_user_" + UUID.randomUUID().toString().replace("-", "");
        String email = name + "@example.vn";
        http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "firstName", "Ánh", "lastName", "Nguyễn", "username", name,
                                "email", email, "password", "Password123"))))
                .andExpect(status().isCreated());
        String response = http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "usernameOrEmail", email, "password", "Password123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(response).path("data").path("accessToken").asText();
    }

    long userId(String token) throws Exception {
        String body = http.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("id").asLong();
    }

    String email(long userId) {
        return database.queryForObject("SELECT email FROM users WHERE id=?", String.class, userId);
    }

    void addOwner(long company, long ownerId) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            database.update("UPDATE companies SET owner_user_id=? WHERE id=?", ownerId, company);
            database.update("""
                    INSERT INTO company_memberships(company_id,user_id,role,status,joined_at)
                    VALUES (?,?,'OWNER','ACTIVE',now())
                    """, company, ownerId);
        });
    }

    UUID saveApplication(Job job, UUID candidateProfile, String candidateName, String appliedAt) {
        return saveApplication(job, candidateProfile, candidateName, appliedAt, UUID.randomUUID());
    }

    UUID saveApplication(Job job, UUID candidateProfile, String candidateName, String appliedAt, UUID id) {
        database.update("""
                INSERT INTO applications(
                    id,candidate_id,job_id,company_id,submitted_revision_id,status,applied_at,updated_at)
                VALUES (?,?,?, ?,?,'SUBMITTED',?::timestamptz,?::timestamptz)
                """, id, candidateProfile, job.id(), job.companyId(), job.revisionId(), appliedAt, appliedAt);
        database.update("""
                INSERT INTO application_snapshots(
                    application_id,full_name,email,phone_number,cover_letter,job_title,company_name,
                    cv_storage_key,cv_file_name,cv_content_type,cv_file_size,captured_at)
                VALUES (?,?, 'candidate@example.vn','0901234567','private note','Backend Engineer',
                    'Private Company',?,'private.pdf',
                    'application/pdf',2048,?::timestamptz)
                """, id, candidateName, "applications/" + id + "/cv/test.pdf", appliedAt);
        return id;
    }

    UUID profile(long user) {
        return database.queryForObject("""
                INSERT INTO candidate_profiles(user_id,phone_number)
                VALUES (?,'0901234567') RETURNING id
                """, UUID.class, user);
    }

    UUID cv(UUID candidate) {
        UUID id = UUID.randomUUID();
        String publicId = "candidates/" + candidate + "/cvs/source";
        database.update("""
                INSERT INTO candidate_cvs(id,candidate_id,file_name,file_size,content_type,public_id,is_default)
                VALUES (?,?,'CV.pdf',2048,?,?,true)
                """, id, candidate, PDF, publicId);
        storage.put(new CandidateCvStorage.StoredCvAsset(publicId, "pdf", PDF, 2_048L, "image", "CV.pdf"));
        return id;
    }

    long company() {
        return database.queryForObject("""
                INSERT INTO companies(name,tax_code,registration_certificate_url,verification_status)
                VALUES ('Công ty Ánh',?,'https://storage.guardwork.vn/license.pdf','VERIFIED') RETURNING id
                """, Long.class, UUID.randomUUID().toString());
    }

    long addRecruiter(long company, long recruiterId) {
        return database.queryForObject("""
                INSERT INTO company_memberships(company_id,user_id,role,status,joined_at)
                VALUES (?,?,'RECRUITER','ACTIVE',now()) RETURNING id
                """, Long.class, company, recruiterId);
    }

    void assign(Job job, long company, long membershipId, long assignedBy) {
        database.update("""
                INSERT INTO job_assignments(job_id,company_id,membership_id,membership_joined_at,assigned_by_user_id,assigned_at)
                SELECT ?,?,?,joined_at,?,now() FROM company_memberships WHERE id=?
                """, job.id(), company, membershipId, assignedBy, membershipId);
    }

    Job publishedJob(long company, long creator) {
        long job = database.queryForObject("""
                INSERT INTO job_postings(company_id,creator_user_id,status,owner_responsible,created_at,updated_at)
                VALUES (?,?,'DRAFT',true,now(),now()) RETURNING id
                """, Long.class, company, creator);
        long revision = database.queryForObject("""
                INSERT INTO job_posting_revisions(
                    job_id,revision_number,content,state,creator_user_id,created_at,
                    submitter_user_id,submitted_at,reviewer_user_id,decided_at)
                VALUES (?,1,'{"title":"Backend Engineer","deadline":"2099-12-01T12:00:00Z"}'::jsonb,'APPROVED',?,now(),?,now(),?,now())
                RETURNING id
                """, Long.class, job, creator, creator, creator);
        database.update("""
                UPDATE job_postings
                SET status='PUBLISHED', published_revision_id=?, approved_deadline='2099-12-01T12:00:00Z'
                WHERE id=?
                """, revision, job);
        return new Job(job, company, revision);
    }

    record Job(long id, long companyId, long revisionId) {
    }

    @TestConfiguration
    static class StorageConfig {
        @Bean
        @Primary
        FakeCvStorage fakeCvStorage() {
            return new FakeCvStorage();
        }
    }

    static final class FakeCvStorage implements CandidateCvStorage {
        private final Map<String, StoredCvAsset> assets = new ConcurrentHashMap<>();
        private volatile boolean failCopy;

        void reset() {
            assets.clear();
            failCopy = false;
        }

        void put(StoredCvAsset asset) {
            assets.put(asset.publicId(), asset);
        }

        void remove(String publicId) {
            assets.remove(publicId);
        }

        boolean has(String publicId) {
            return assets.containsKey(publicId);
        }

        void failCopy() {
            failCopy = true;
        }

        @Override
        public UploadSignature createUploadSignature(String publicId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredCvAsset> find(String publicId) {
            return Optional.ofNullable(assets.get(publicId));
        }

        @Override
        public void delete(String publicId, String resourceType) {
            assets.remove(publicId);
        }

        @Override
        public StoredCvAsset copy(String sourcePublicId, String targetPublicId, String resourceType, String format) {
            if (failCopy) {
                throw new AppException(ErrorCode.APPLICATION_CV_COPY_FAILED);
            }
            StoredCvAsset source = assets.get(sourcePublicId);
            if (source == null) {
                throw new AppException(ErrorCode.CV_NOT_FOUND);
            }
            StoredCvAsset copy = new StoredCvAsset(
                    targetPublicId, format, source.contentType(), source.bytes(), resourceType, source.originalFileName());
            assets.put(targetPublicId, copy);
            return copy;
        }

        @Override
        public String createDownloadUrl(String publicId, String resourceType, String format, Duration ttl) {
            return "https://fake.example/" + publicId;
        }
    }
}
