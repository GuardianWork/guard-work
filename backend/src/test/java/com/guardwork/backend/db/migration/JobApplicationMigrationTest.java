package com.guardwork.backend.db.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL integrity boundary for UC-CAN-11 applications and immutable snapshots. */
@SpringBootTest
@ActiveProfiles("test")
class JobApplicationMigrationTest {

    private static final String PDF = "application/pdf";

    @Autowired JdbcTemplate database;

    @Test
    void candidateCanApplyToAJobOnlyOnce() {
        UUID candidate = profile(user());
        Job job = publishedJob(company(), user());
        insertApplication(candidate, job);

        assertThatThrownBy(() -> insertApplication(candidate, job))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_applications_candidate_job");
    }

    @Test
    void applicationIdentityCannotBeRewrittenOrDeleted() {
        UUID candidate = profile(user());
        Job job = publishedJob(company(), user());
        UUID application = insertApplication(candidate, job);

        assertThatThrownBy(() -> database.update(
                "UPDATE applications SET candidate_id=? WHERE id=?", UUID.randomUUID(), application))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("identity");
        assertThatThrownBy(() -> database.update(
                "DELETE FROM applications WHERE id=?", application))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    void submittedSnapshotCannotBeChangedOrDeleted() {
        UUID candidate = profile(user());
        Job job = publishedJob(company(), user());
        UUID application = insertApplication(candidate, job);
        insertSnapshot(application, "0901234567", PDF, 1_024L, UUID.randomUUID());

        assertThatThrownBy(() -> database.update(
                "UPDATE application_snapshots SET full_name='Changed' WHERE application_id=?", application))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> database.update(
                "DELETE FROM application_snapshots WHERE application_id=?", application))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void snapshotRejectsUnsafePhoneCvTypeAndSize() {
        UUID candidate = profile(user());
        Job job = publishedJob(company(), user());
        UUID first = insertApplication(candidate, job);
        UUID second = insertApplication(profile(user()), job);
        UUID third = insertApplication(profile(user()), job);

        assertThatThrownBy(() -> insertSnapshot(first, "12345", PDF, 1_024L, UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("phone_number");
        assertThatThrownBy(() -> insertSnapshot(second, "0901234567", "text/plain", 1_024L, UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cv_content_type");
        assertThatThrownBy(() -> insertSnapshot(third, "0901234567", PDF, 0L, UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cv_file_size");
    }

    @Test
    void sourceCvIdIsHistoricalAndDoesNotRequireACvRow() {
        UUID candidate = profile(user());
        Job job = publishedJob(company(), user());
        UUID application = insertApplication(candidate, job);
        UUID deletedCvId = UUID.randomUUID();

        insertSnapshot(application, "0901234567", PDF, 1_024L, deletedCvId);

        assertThat(count("SELECT count(*) FROM application_snapshots WHERE application_id=?", application))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM candidate_cvs WHERE id=?", deletedCvId)).isZero();
    }

    @Test
    void snapshotRequiresAnApplicationAndApplicationRequiresARealJob() {
        assertThatThrownBy(() -> insertSnapshot(
                UUID.randomUUID(), "0901234567", PDF, 1_024L, UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("application_id");

        UUID candidate = profile(user());
        assertThatThrownBy(() -> database.update("""
                INSERT INTO applications(id,candidate_id,job_id,company_id,submitted_revision_id,status,applied_at,updated_at)
                VALUES (?,?,999999,999999,999999,'SUBMITTED',now(),now())
                """, UUID.randomUUID(), candidate))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_applications_job_company");
    }

    @Test
    void candidateProfileWithApplicationsCannotBeDeletedSilently() {
        UUID candidate = profile(user());
        Job job = publishedJob(company(), user());
        insertApplication(candidate, job);

        assertThatThrownBy(() -> database.update("DELETE FROM candidate_profiles WHERE id=?", candidate))
                .isInstanceOf(DataAccessException.class);
    }

    long user() {
        String identity = "app_" + UUID.randomUUID().toString().replace("-", "");
        return database.queryForObject(
                "INSERT INTO users(first_name,last_name,username,email,password) VALUES ('Ánh','Nguyễn',?,?,'fixture') RETURNING id",
                Long.class, identity, identity + "@example.vn");
    }

    long company() {
        return database.queryForObject(
                "INSERT INTO companies(name,tax_code,registration_certificate_url) VALUES ('Công ty Ánh',?,'https://storage.guardwork.vn/license.pdf') RETURNING id",
                Long.class, UUID.randomUUID().toString());
    }

    UUID profile(long user) {
        return database.queryForObject(
                "INSERT INTO candidate_profiles(user_id,full_name) VALUES (?,'Nguyễn Văn An') RETURNING id",
                UUID.class, user);
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

    UUID insertApplication(UUID candidate, Job job) {
        UUID id = UUID.randomUUID();
        database.update("""
                INSERT INTO applications(id,candidate_id,job_id,company_id,submitted_revision_id,status,applied_at,updated_at)
                VALUES (?,?,?,?,?,'SUBMITTED',now(),now())
                """, id, candidate, job.id(), job.companyId(), job.revisionId());
        return id;
    }

    void insertSnapshot(UUID application, String phone, String contentType, long size, UUID sourceCvId) {
        database.update("""
                INSERT INTO application_snapshots(
                    application_id,full_name,email,phone_number,cover_letter,job_title,company_name,
                    source_cv_id,cv_storage_key,cv_file_name,cv_content_type,cv_file_size,captured_at)
                VALUES (?,'Nguyễn Văn An','an@example.vn',?,'Em quan tâm','Backend Engineer','Công ty Ánh',
                    ?,'applications/id/cv/copy.pdf','cv.pdf',?,?,now())
                """, application, phone, sourceCvId, contentType, size);
    }

    long count(String sql, Object... arguments) {
        return database.queryForObject(sql, Long.class, arguments);
    }

    record Job(long id, long companyId, long revisionId) {
    }
}
