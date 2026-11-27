package com.guardwork.backend.application.repository;

import com.guardwork.backend.application.model.JobApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobApplicationRepository extends JpaRepository<JobApplication, UUID> {

    // Share predicates between page and count so totals cannot include inaccessible jobs.
    String COMPANY_LIST_FROM = """
            FROM applications a
            JOIN application_snapshots s ON s.application_id = a.id
            JOIN job_postings j ON j.id = a.job_id AND j.company_id = a.company_id
            WHERE a.company_id = :companyId
              AND NOT j.staff_suspended AND j.status <> 'SUSPENDED'
              AND (CAST(:jobId AS bigint) IS NULL OR a.job_id = :jobId)
              AND (:owner = true OR EXISTS (
                  SELECT 1 FROM job_assignments ja
                  WHERE ja.job_id = a.job_id AND ja.company_id = a.company_id
                    AND ja.membership_id = :membershipId
                    AND ja.membership_joined_at = :joinedAt AND ja.ended_at IS NULL))
              AND (CAST(:search AS text) IS NULL OR
                   unaccent(LOWER(s.full_name)) LIKE
                   unaccent(LOWER(CONCAT('%', :search, '%'))) ESCAPE '\\')
              AND (CAST(:status AS text) IS NULL OR a.status = :status)
            """;

    @Query(value = """
            SELECT a.id AS id, s.full_name AS "candidateName", a.status AS status,
                   a.applied_at AS "appliedAt", a.job_id AS "jobId", s.job_title AS "jobTitle"
            """ + COMPANY_LIST_FROM + " ORDER BY a.applied_at DESC, a.id DESC",
            countQuery = "SELECT count(*) " + COMPANY_LIST_FROM, nativeQuery = true)
    Page<CompanyApplicationProjection> findCompanyList(
            @Param("companyId") Long companyId, @Param("owner") boolean owner,
            @Param("membershipId") Long membershipId, @Param("joinedAt") java.time.Instant joinedAt,
            @Param("jobId") Long jobId, @Param("search") String search,
            @Param("status") String status, Pageable pageable);

    boolean existsByCandidateIdAndJobId(UUID candidateId, Long jobId);

    Optional<JobApplication> findByCandidateIdAndJobId(UUID candidateId, Long jobId);

    Optional<JobApplication> findByIdAndCandidateId(UUID id, UUID candidateId);

    /**
     * Claims active Applications whose last status transition is at or before the cutoff.
     * The worker re-checks after locking so a concurrent update is never overwritten.
     */
    @Query(value = """
            SELECT application.id
            FROM applications application
            WHERE application.status IN ('SUBMITTED','UNDER_REVIEW','SHORTLISTED','INTERVIEWING')
              AND application.status_changed_at <= :cutoff
            ORDER BY application.status_changed_at
            LIMIT :batchSize
            FOR UPDATE OF application SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> findInactiveApplicationIds(
            @Param("cutoff") java.time.Instant cutoff, @Param("batchSize") int batchSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from JobApplication a where a.id=:id")
    Optional<JobApplication> findByIdForUpdate(@Param("id") UUID id);

    @Query("select a.jobId as jobId, a.companyId as companyId, a.candidateId as candidateId "
            + "from JobApplication a where a.id=:id")
    Optional<ApplicationRouteProjection> findRouteById(@Param("id") UUID id);

    List<JobApplication> findByCandidateIdOrderByAppliedAtDesc(UUID candidateId);

    // One Application per Candidate/job is enforced by uk_applications_candidate_job.
    // Do not filter Application status: even terminal applicants retain their own history.
    @Query(value = """
            SELECT DISTINCT u.id AS "userId", a.id AS "applicationId"
            FROM applications a
            JOIN candidate_profiles cp ON cp.id = a.candidate_id
            JOIN users u ON u.id = cp.user_id
            WHERE a.job_id = :jobId AND a.company_id = :companyId
              AND u.status = 'ACTIVE' AND u.self_service_state = 'ACTIVE'
            """, nativeQuery = true)
    List<ApplicationNotificationRecipient> findCandidateNotificationRecipients(
            @Param("jobId") Long jobId, @Param("companyId") Long companyId);

    @Query(value = """
            SELECT a.id AS id, a.job_id AS "jobId", a.version AS version, s.job_title AS "jobTitle", s.company_name AS "companyName",
                   s.job_location AS "jobLocation", s.work_mode AS "workMode", s.job_deadline AS "jobDeadline",
                   a.status AS status, a.applied_at AS "appliedAt", s.cv_file_name AS "cvFileName",
                   (jp.status = 'SUSPENDED') AS "jobSuspended"
            FROM applications a
            JOIN application_snapshots s ON s.application_id = a.id
            JOIN job_postings jp ON jp.id = a.job_id AND jp.company_id = a.company_id
            WHERE a.candidate_id = :candidateId
              AND jp.staff_suspended = FALSE
              AND (CAST(:search AS text) IS NULL OR
                   unaccent(LOWER(s.job_title)) LIKE unaccent(LOWER(CONCAT('%', :search, '%'))) ESCAPE '\\'
                   OR unaccent(LOWER(s.company_name)) LIKE unaccent(LOWER(CONCAT('%', :search, '%'))) ESCAPE '\\')
              AND (CAST(:status AS text) IS NULL OR a.status = :status)
            ORDER BY a.applied_at DESC, a.id DESC
            """,
            countQuery = """
                    SELECT count(*)
                    FROM applications a
                    JOIN application_snapshots s ON s.application_id = a.id
                    JOIN job_postings jp ON jp.id = a.job_id AND jp.company_id = a.company_id
                    WHERE a.candidate_id = :candidateId
                      AND jp.staff_suspended = FALSE
                      AND (CAST(:search AS text) IS NULL OR
                           unaccent(LOWER(s.job_title)) LIKE unaccent(LOWER(CONCAT('%', :search, '%'))) ESCAPE '\\'
                           OR unaccent(LOWER(s.company_name)) LIKE unaccent(LOWER(CONCAT('%', :search, '%'))) ESCAPE '\\')
                      AND (CAST(:status AS text) IS NULL OR a.status = :status)
                    """,
            nativeQuery = true)
    Page<CandidateApplicationProjection> findCandidateList(
            @Param("candidateId") UUID candidateId,
            @Param("search") String search,
            @Param("status") String status,
            Pageable pageable);

    @Query(value = """
            SELECT a.id AS id, s.full_name AS "candidateName", a.status AS status,
                   a.applied_at AS "appliedAt"
            FROM applications a
            JOIN application_snapshots s ON s.application_id = a.id
            WHERE a.company_id = :companyId AND a.job_id = :jobId
              AND (CAST(:search AS text) IS NULL OR
                   unaccent(LOWER(s.full_name)) LIKE
                   unaccent(LOWER(CONCAT('%', :search, '%'))) ESCAPE '\\')
              AND (CAST(:status AS text) IS NULL OR a.status = :status)
            ORDER BY a.applied_at DESC, a.id DESC
            """,
            countQuery = """
                    SELECT count(*)
                    FROM applications a
                    JOIN application_snapshots s ON s.application_id = a.id
                    WHERE a.company_id = :companyId AND a.job_id = :jobId
                      AND (CAST(:search AS text) IS NULL OR
                           unaccent(LOWER(s.full_name)) LIKE
                           unaccent(LOWER(CONCAT('%', :search, '%'))) ESCAPE '\\')
                      AND (CAST(:status AS text) IS NULL OR a.status = :status)
                    """,
            nativeQuery = true)
    Page<RecruiterApplicationProjection> findRecruiterList(
            @Param("companyId") Long companyId,
            @Param("jobId") Long jobId,
            @Param("search") String search,
            @Param("status") String status,
            Pageable pageable);
}
