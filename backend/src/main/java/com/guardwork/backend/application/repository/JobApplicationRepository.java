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

    boolean existsByCandidateIdAndJobId(UUID candidateId, Long jobId);

    Optional<JobApplication> findByCandidateIdAndJobId(UUID candidateId, Long jobId);

    Optional<JobApplication> findByIdAndCandidateId(UUID id, UUID candidateId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from JobApplication a where a.id=:id")
    Optional<JobApplication> findByIdForUpdate(@Param("id") UUID id);

    @Query("select a.jobId as jobId, a.companyId as companyId, a.candidateId as candidateId "
            + "from JobApplication a where a.id=:id")
    Optional<ApplicationRouteProjection> findRouteById(@Param("id") UUID id);

    List<JobApplication> findByCandidateIdOrderByAppliedAtDesc(UUID candidateId);

    @Query(value = """
            SELECT a.id AS id, a.job_id AS "jobId", a.version AS version, s.job_title AS "jobTitle", s.company_name AS "companyName",
                   a.status AS status, a.applied_at AS "appliedAt", s.cv_file_name AS "cvFileName"
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
