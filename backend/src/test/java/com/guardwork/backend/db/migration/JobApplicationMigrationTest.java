package com.guardwork.backend.db.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.guardwork.backend.candidate.cvs.CandidateCvAccessGrantRepository;
import com.guardwork.backend.candidate.cvs.CandidateCvRepository;
import com.guardwork.backend.application.repository.ApplicationSnapshotRepository;
import com.guardwork.backend.application.repository.JobApplicationRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL integrity boundary for UC-CAN-11 applications and immutable snapshots. */
@SpringBootTest
@ActiveProfiles("test")
class JobApplicationMigrationTest {

    private static final String PDF = "application/pdf";

    @Autowired JdbcTemplate database;
    @Autowired CandidateCvRepository cvs;
    @Autowired CandidateCvAccessGrantRepository grants;
    @Autowired ApplicationSnapshotRepository snapshots;
    @Autowired JobApplicationRepository applications;

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

    @Test
    void existingSnapshotsAreMarkedAsLegacyCopies() {
        UUID candidate = profile(user());
        Job job = publishedJob(company(), user());
        UUID application = insertApplication(candidate, job);
        insertSnapshot(application, "0901234567", PDF, 1_024L, UUID.randomUUID());

        assertThat(database.queryForObject(
                "SELECT cv_storage_mode FROM application_snapshots WHERE application_id=?",
                String.class, application)).isEqualTo("LEGACY_COPY");
    }

    @Test
    void sharedSnapshotModeRequiresAValidValue() {
        UUID candidate = profile(user());
        Job job = publishedJob(company(), user());
        UUID application = insertApplication(candidate, job);

        assertThatThrownBy(() -> database.update("""
                INSERT INTO application_snapshots(
                    application_id,full_name,email,phone_number,cover_letter,job_title,company_name,
                    cv_storage_key,cv_storage_mode,cv_file_name,cv_content_type,cv_file_size,captured_at)
                VALUES (?,'Nguyễn Văn An','an@example.vn','0901234567','Em quan tâm','Backend Engineer','Công ty Ánh',
                    'candidates/x/cvs/y','BROKEN','cv.pdf',?,1024,now())
                """, application, PDF))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cv_storage_mode");
    }

    @Test
    void removedCvKeepsATombstoneAndFreesTheDefaultSlot() {
        UUID candidate = profile(user());
        UUID removed = insertCv(candidate, true);
        database.update("UPDATE candidate_cvs SET deleted_at=now() WHERE id=?", removed);

        UUID replacement = insertCv(candidate, true);

        assertThat(count("SELECT count(*) FROM candidate_cvs WHERE candidate_id=?", candidate)).isEqualTo(2);
        assertThat(database.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM candidate_cvs WHERE id=?", Boolean.class, removed)).isTrue();
        assertThat(database.queryForObject(
                "SELECT is_default FROM candidate_cvs WHERE id=?", Boolean.class, replacement)).isTrue();
    }

    @Test
    void twoActiveDefaultCvsAreRejected() {
        UUID candidate = profile(user());
        insertCv(candidate, true);

        assertThatThrownBy(() -> insertCv(candidate, true))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_candidate_cvs_one_default");
    }

    @Test
    void accessGrantIsUniquePerApplicationAndValidatesRevocationPairing() {
        UUID candidate = profile(user());
        Job job = publishedJob(company(), user());
        UUID application = insertApplication(candidate, job);
        UUID cv = insertCv(candidate, true);
        insertGrant(application, cv, candidate, job.companyId(), null, null);

        assertThatThrownBy(() -> insertGrant(application, cv, candidate, job.companyId(), null, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_cv_grant_application");
        assertThatThrownBy(() -> database.update("""
                UPDATE candidate_cv_access_grants SET revoked_at=now() WHERE application_id=?
                """, application))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_cv_grant_revocation");
    }

    @Test
    void accessGrantCannotBeDeletedRewrittenOrBroughtBack() {
        UUID candidate = profile(user());
        Job job = publishedJob(company(), user());
        UUID application = insertApplication(candidate, job);
        UUID cv = insertCv(candidate, true);
        insertGrant(application, cv, candidate, job.companyId(), null, null);

        assertThatThrownBy(() -> database.update("""
                UPDATE candidate_cv_access_grants SET storage_key='candidates/other/cvs/x' WHERE application_id=?
                """, application))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> database.update("""
                DELETE FROM candidate_cv_access_grants WHERE application_id=?
                """, application))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be deleted");

        database.update("""
                UPDATE candidate_cv_access_grants SET expires_at=now() + interval '30 days'
                WHERE application_id=?
                """, application);
        assertThatThrownBy(() -> database.update("""
                UPDATE candidate_cv_access_grants SET expires_at=now() + interval '60 days'
                WHERE application_id=?
                """, application))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("expiry");

        database.update("""
                UPDATE candidate_cv_access_grants
                SET revoked_at=now(), revoked_reason='APPLICATION_CLOSED' WHERE application_id=?
                """, application);
        assertThatThrownBy(() -> database.update("""
                UPDATE candidate_cv_access_grants SET revoked_at=now() + interval '1 day'
                WHERE application_id=?
                """, application))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("revocation");
    }

    @Test
    void expiredOrRevokedGrantsAreNotLive() {
        UUID candidate = profile(user());
        long creator = user();
        long company = company();
        Job openJob = publishedJob(company, creator);
        Job expiredJob = publishedJob(company, creator);
        Job revokedJob = publishedJob(company, creator);
        UUID openApplication = insertApplication(candidate, openJob);
        UUID expiredApplication = insertApplication(candidate, expiredJob);
        UUID revokedApplication = insertApplication(candidate, revokedJob);
        UUID cv = insertCv(candidate, true);
        Instant now = Instant.parse("2026-11-17T10:00:00Z");

        insertGrant(openApplication, cv, candidate, company, null, null);
        insertGrant(expiredApplication, cv, candidate, company, now.minusSeconds(120),
                now.minusSeconds(60), null);
        insertGrant(revokedApplication, cv, candidate, company, now.minusSeconds(120), null,
                now.minusSeconds(60));

        assertThat(grants.findLive(openApplication, company, now)).isPresent();
        assertThat(grants.findLive(expiredApplication, company, now)).isEmpty();
        assertThat(grants.findLive(revokedApplication, company, now)).isEmpty();
        assertThat(grants.findLive(openApplication, company + 1, now)).isEmpty();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void onlyRemovedCvsWithoutLiveGrantsArePurgeable() {
        UUID candidate = profile(user());
        long creator = user();
        long company = company();
        Job openJob = publishedJob(company, creator);
        Job expiredJob = publishedJob(company, creator);
        Job inactiveJob = publishedJob(company, creator);
        UUID openApplication = insertApplication(candidate, openJob);
        UUID expiredApplication = insertApplication(candidate, expiredJob);
        UUID inactiveApplication = insertApplication(candidate, inactiveJob);
        database.update("UPDATE applications SET status='INACTIVE' WHERE id=?", inactiveApplication);
        UUID cv = insertCv(candidate, true);
        Instant now = Instant.now();
        insertGrant(openApplication, cv, candidate, company, null, null);
        insertGrant(expiredApplication, cv, candidate, company, now.minusSeconds(120),
                now.minusSeconds(60), null);
        insertGrant(inactiveApplication, cv, candidate, company, null, null);
        database.update("UPDATE candidate_cvs SET deleted_at=now() WHERE id=?", cv);

        assertThat(cvs.findPurgeableCvIds(20)).doesNotContain(cv);

        grants.revoke(openApplication, now, "APPLICATION_CLOSED");

        assertThat(cvs.findPurgeableCvIds(20)).contains(cv);
        assertThat(cvs.markStorageDeleted(cv, now)).isEqualTo(1);
        assertThat(cvs.findPurgeableCvIds(20)).doesNotContain(cv);
    }

    @Test
    void inactiveStatusAndHistoryEdgesAreAcceptedWithoutDatabaseEnumChecks() {
        UUID candidate = profile(user());
        Job job = publishedJob(company(), user());
        UUID application = insertApplication(candidate, job);

        database.update("UPDATE applications SET status='INACTIVE' WHERE id=?", application);
        database.update("""
                INSERT INTO application_status_history(application_id,from_status,to_status,occurred_at)
                VALUES (?, 'SUBMITTED','INACTIVE', now())
                """, application);

        assertThat(count("SELECT count(*) FROM application_status_history WHERE application_id=?",
                application)).isEqualTo(1);

        // Transition legality is enforced by the application layer, not the database.
        database.update("""
                INSERT INTO application_status_history(application_id,from_status,to_status,occurred_at)
                VALUES (?, 'INACTIVE','HIRED', now())
                """, application);
        database.update("""
                INSERT INTO application_status_history(application_id,from_status,to_status,occurred_at)
                VALUES (?, 'INACTIVE','UNDER_REVIEW', now())
                """, application);
        assertThat(count("SELECT count(*) FROM application_status_history WHERE application_id=?",
                application)).isEqualTo(3);

        // Enum values are validated by the application layer, so the database
        // accepts unknown statuses.
        database.update("UPDATE applications SET status='BROKEN' WHERE id=?", application);
        assertThat(database.queryForObject(
                "SELECT status FROM applications WHERE id=?", String.class, application))
                .isEqualTo("BROKEN");
    }

    @Test
    void statusChangedAtIsBackedByTheDatabaseClockAndCannotBeNull() {
        UUID candidate = profile(user());
        Job job = publishedJob(company(), user());
        UUID application = insertApplication(candidate, job);

        assertThat(database.queryForObject(
                "SELECT status_changed_at FROM applications WHERE id=?",
                Timestamp.class, application)).isNotNull();
        assertThatThrownBy(() -> database.update(
                "UPDATE applications SET status_changed_at=NULL WHERE id=?", application))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("status_changed_at");
    }

    @Test
    void grantEpochsAllowReactivationButOnlyOneLiveGrant() {
        UUID candidate = profile(user());
        long creator = user();
        long company = company();
        Job job = publishedJob(company, creator);
        UUID application = insertApplication(candidate, job);
        UUID cv = insertCv(candidate, true);

        insertGrant(application, cv, candidate, company, null, null);
        assertThatThrownBy(() -> insertGrantWithEpoch(application, cv, candidate, company, 2))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_cv_grant_");

        database.update("""
                UPDATE candidate_cv_access_grants
                SET revoked_at=now(), revoked_reason='APPLICATION_INACTIVE'
                WHERE application_id=?
                """, application);
        insertGrantWithEpoch(application, cv, candidate, company, 2);

        assertThat(count("SELECT count(*) FROM candidate_cv_access_grants WHERE application_id=?",
                application)).isEqualTo(2);
        assertThatThrownBy(() -> insertGrantWithEpoch(application, cv, candidate, company, 2))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_cv_grant_");

        database.update("""
                UPDATE candidate_cv_access_grants
                SET revoked_at=now(), revoked_reason='CANDIDATE_CV_DELETED'
                WHERE application_id=? AND epoch=2
                """, application);
    }

    @Test
    void snapshotPurgeMarkerIsTheOnlyAllowedMutation() {
        UUID candidate = profile(user());
        Job job = publishedJob(company(), user());
        UUID application = insertApplication(candidate, job);
        insertSnapshot(application, "0901234567", PDF, 1_024L, UUID.randomUUID());

        assertThatThrownBy(() -> database.update("""
                UPDATE application_snapshots SET cv_file_name='other.pdf' WHERE application_id=?
                """, application))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");

        database.update("UPDATE application_snapshots SET cv_purged_at=now() WHERE application_id=?",
                application);
        assertThatThrownBy(() -> database.update(
                "UPDATE application_snapshots SET cv_purged_at=now() WHERE application_id=?",
                application))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("CV purge marker is final");
        assertThatThrownBy(() -> database.update(
                "UPDATE application_snapshots SET cv_purged_at=NULL WHERE application_id=?",
                application))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("CV purge marker is final");
        assertThatThrownBy(() -> database.update(
                "DELETE FROM application_snapshots WHERE application_id=?", application))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void rejectedLegacyPurgeQueryClaimsOnlyDueRows() {
        UUID candidate = profile(user());
        long creator = user();
        long company = company();

        UUID rejectedLegacy = insertApplication(candidate, publishedJob(company, creator));
        database.update("UPDATE applications SET status='REJECTED' WHERE id=?", rejectedLegacy);
        insertSnapshot(rejectedLegacy, "0901234567", PDF, 1_024L, UUID.randomUUID());

        UUID activeLegacy = insertApplication(candidate, publishedJob(company, creator));
        insertSnapshot(activeLegacy, "0901234567", PDF, 1_024L, UUID.randomUUID());

        UUID rejectedShared = insertApplication(candidate, publishedJob(company, creator));
        database.update("UPDATE applications SET status='REJECTED' WHERE id=?", rejectedShared);
        insertSnapshot(rejectedShared, "0901234567", PDF, 1_024L, UUID.randomUUID(), "SHARED");

        UUID alreadyPurged = insertApplication(candidate, publishedJob(company, creator));
        database.update("UPDATE applications SET status='REJECTED' WHERE id=?", alreadyPurged);
        insertSnapshot(alreadyPurged, "0901234567", PDF, 1_024L, UUID.randomUUID());
        database.update("UPDATE application_snapshots SET cv_purged_at=now() WHERE application_id=?",
                alreadyPurged);

        assertThat(snapshots.findRejectedLegacyCvPurgeIds(100))
                .contains(rejectedLegacy)
                .doesNotContain(activeLegacy, rejectedShared, alreadyPurged);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void inactivityQuerySelectsOnlyStaleActiveApplications() {
        UUID candidate = profile(user());
        long creator = user();
        long company = company();
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(30L * 24 * 60 * 60);
        Instant stale = now.minusSeconds(40L * 24 * 60 * 60);

        UUID staleApplication = insertApplication(candidate, publishedJob(company, creator));
        database.update("UPDATE applications SET status='SUBMITTED', status_changed_at=? WHERE id=?",
                Timestamp.from(stale), staleApplication);

        UUID recentApplication = insertApplication(candidate, publishedJob(company, creator));
        database.update("UPDATE applications SET status='UNDER_REVIEW', status_changed_at=? WHERE id=?",
                Timestamp.from(now), recentApplication);

        UUID inactiveApplication = insertApplication(candidate, publishedJob(company, creator));
        database.update("UPDATE applications SET status='INACTIVE', status_changed_at=? WHERE id=?",
                Timestamp.from(stale), inactiveApplication);

        UUID hiredApplication = insertApplication(candidate, publishedJob(company, creator));
        database.update("UPDATE applications SET status='HIRED', status_changed_at=? WHERE id=?",
                Timestamp.from(stale), hiredApplication);

        assertThat(applications.findInactiveApplicationIds(cutoff, 100))
                .contains(staleApplication)
                .doesNotContain(recentApplication, inactiveApplication, hiredApplication);
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
                "INSERT INTO candidate_profiles(user_id) VALUES (?) RETURNING id",
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
        insertSnapshot(application, phone, contentType, size, sourceCvId, "LEGACY_COPY");
    }

    void insertSnapshot(UUID application, String phone, String contentType, long size, UUID sourceCvId,
            String mode) {
        database.update("""
                INSERT INTO application_snapshots(
                    application_id,full_name,email,phone_number,cover_letter,job_title,company_name,
                    source_cv_id,cv_storage_key,cv_storage_mode,cv_file_name,cv_content_type,cv_file_size,captured_at)
                VALUES (?,'Nguyễn Văn An','an@example.vn',?,'Em quan tâm','Backend Engineer','Công ty Ánh',
                    ?,'applications/id/cv/copy.pdf',?,'cv.pdf',?,?,now())
                """, application, phone, sourceCvId, mode, contentType, size);
    }

    UUID insertGrantWithEpoch(UUID application, UUID cv, UUID candidate, long company, int epoch) {
        UUID id = UUID.randomUUID();
        database.update("""
                INSERT INTO candidate_cv_access_grants(
                    id,application_id,epoch,cv_id,candidate_id,company_id,storage_key,resource_type,
                    file_name,content_type,file_size,granted_at)
                VALUES (?,?,?,?,?,?,?,'image','cv.pdf',?,1024,now())
                """, id, application, epoch, cv, candidate, company,
                "candidates/" + candidate + "/cvs/source", PDF);
        return id;
    }

    UUID insertCv(UUID candidate, boolean isDefault) {
        UUID id = UUID.randomUUID();
        database.update("""
                INSERT INTO candidate_cvs(id,candidate_id,file_name,file_size,content_type,public_id,is_default)
                VALUES (?,?,'cv.pdf',1024,?,?,?)
                """, id, candidate, PDF, "candidates/" + candidate + "/cvs/" + id, isDefault);
        return id;
    }

    UUID insertGrant(UUID application, UUID cv, UUID candidate, long company,
                     Instant grantedAt, Instant expiresAt) {
        return insertGrant(application, cv, candidate, company, grantedAt, expiresAt, null);
    }

    UUID insertGrant(UUID application, UUID cv, UUID candidate, long company,
                     Instant grantedAt, Instant expiresAt, Instant revokedAt) {
        UUID id = UUID.randomUUID();
        Timestamp granted = Timestamp.from(grantedAt == null ? Instant.now() : grantedAt);
        Timestamp expires = expiresAt == null ? null : Timestamp.from(expiresAt);
        Timestamp revoked = revokedAt == null ? null : Timestamp.from(revokedAt);
        database.update("""
                INSERT INTO candidate_cv_access_grants(
                    id,application_id,cv_id,candidate_id,company_id,storage_key,resource_type,
                    file_name,content_type,file_size,granted_at,expires_at,revoked_at,revoked_reason)
                VALUES (?,?,?,?,?,?,'image','cv.pdf',?,1024,?,?,?,?)
                """, id, application, cv, candidate, company,
                "candidates/" + candidate + "/cvs/source", PDF, granted, expires, revoked,
                revoked == null ? null : "ADMIN");
        return id;
    }

    long count(String sql, Object... arguments) {
        return database.queryForObject(sql, Long.class, arguments);
    }

    record Job(long id, long companyId, long revisionId) {
    }
}
