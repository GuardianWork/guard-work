package com.guardwork.backend.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.candidate.cvs.CandidateCvStorage;
import com.guardwork.backend.common.exception.AppException;
import com.guardwork.backend.common.exception.ErrorCode;
import com.guardwork.backend.user.AccountPhone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final String FORM = APPLICATIONS + "/form";
    private static final String PDF = "application/pdf";

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate database;
    @Autowired FakeCvStorage storage;

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

        http.perform(get(FORM).param("jobId", Long.toString(job.id())).header("Authorization", candidate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.title").value("Backend Engineer"))
                .andExpect(jsonPath("$.data.profile.fullName").value("Nguyễn Văn An"))
                .andExpect(jsonPath("$.data.profile.email").value(email(userId)))
                .andExpect(jsonPath("$.data.profile.phoneNumber").value(accountPhone))
                .andExpect(jsonPath("$.data.cvs[0].id").value(cv.toString()))
                .andExpect(jsonPath("$.data.defaultCvId").value(cv.toString()))
                .andExpect(jsonPath("$.data.alreadyApplied").value(false));

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

        http.perform(get(FORM).param("jobId", Long.toString(job.id())).header("Authorization", candidate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyApplied").value(true));

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
        http.perform(get(FORM).param("jobId", "1")).andExpect(status().isUnauthorized());
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
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("40304 ACCOUNT_SUSPENDED: Account is suspended"));
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

    UUID profile(long user) {
        return database.queryForObject("""
                INSERT INTO candidate_profiles(user_id,full_name,phone_number)
                VALUES (?,'Nguyễn Văn An','0901234567') RETURNING id
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
