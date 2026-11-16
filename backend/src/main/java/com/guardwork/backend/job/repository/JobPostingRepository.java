package com.guardwork.backend.job.repository;

import com.guardwork.backend.job.model.JobPosting;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface JobPostingRepository
    extends JpaRepository<JobPosting, Long>, JpaSpecificationExecutor<JobPosting> {
  interface ApplicationCount {
    Long getJobId();
    long getTotal();
  }

  interface PublicCompanyGroup {
    Long getCompanyId();
    String getCompanyName();
    long getRoleCount();
  }

  /**
   * Shared visibility boundary for every candidate-facing projection. Company search and dossier
   * reads must not diverge from job search, so both use the same joins and predicates below.
   */
  String PUBLIC_POSTING_FROM = " FROM job_postings jp "
      + "JOIN job_posting_revisions r ON r.id = jp.published_revision_id "
      + "JOIN companies c ON c.id = jp.company_id "
      + "WHERE jp.status = 'PUBLISHED' "
      + "AND jp.staff_suspended = FALSE "
      + "AND (jp.approved_deadline IS NULL OR jp.approved_deadline > :now) "
      + "AND c.verification_status = 'VERIFIED' "
      + "AND c.is_banned = FALSE ";

  String PUBLIC_SEARCH_FILTER =
      "AND (CAST(:keyword AS text) IS NULL OR "
          + "unaccent(LOWER(COALESCE(r.content ->> 'title', ''))) LIKE unaccent(LOWER(CONCAT('%', :keyword, '%'))) ESCAPE '\\' OR "
          + "unaccent(LOWER(COALESCE(r.content ->> 'description', ''))) LIKE unaccent(LOWER(CONCAT('%', :keyword, '%'))) ESCAPE '\\' OR "
          + "unaccent(LOWER(COALESCE(r.content ->> 'location', ''))) LIKE unaccent(LOWER(CONCAT('%', :keyword, '%'))) ESCAPE '\\') "
          + "AND (CAST(:location AS text) IS NULL OR unaccent(LOWER(COALESCE(r.content ->> 'location', ''))) LIKE unaccent(LOWER(CONCAT('%', :location, '%'))) ESCAPE '\\') "
          + "AND (CAST(:industry AS text) IS NULL OR unaccent(LOWER(COALESCE(r.content ->> 'industry', ''))) LIKE unaccent(LOWER(CONCAT('%', :industry, '%'))) ESCAPE '\\') "
          + "AND (CAST(:employmentType AS text) IS NULL OR UPPER(COALESCE(r.content ->> 'employmentType', '')) = UPPER(:employmentType)) "
          + "AND (CAST(:workArrangement AS text) IS NULL OR UPPER(COALESCE(r.content ->> 'workArrangement', '')) = UPPER(:workArrangement)) "
          + "AND (CAST(:salaryMin AS numeric) IS NULL OR ((r.content -> 'compensation' ->> 'disclosed') = 'true' AND (r.content -> 'compensation' ->> 'maximum')::NUMERIC >= :salaryMin)) "
          + "AND (CAST(:salaryMax AS numeric) IS NULL OR ((r.content -> 'compensation' ->> 'disclosed') = 'true' AND (r.content -> 'compensation' ->> 'minimum')::NUMERIC <= :salaryMax)) "
          + "AND (CAST(:deadlineFilter AS timestamp with time zone) IS NULL OR (jp.approved_deadline IS NOT NULL AND jp.approved_deadline <= CAST(:deadlineFilter AS timestamp with time zone))) ";

  String PUBLIC_COMPANY_SEARCH_FILTER =
      "AND (CAST(:keyword AS text) IS NULL OR "
          + "unaccent(LOWER(c.name)) LIKE unaccent(LOWER(CONCAT('%', :keyword, '%'))) ESCAPE '\\' OR "
          + "unaccent(LOWER(COALESCE(r.content ->> 'title', ''))) LIKE unaccent(LOWER(CONCAT('%', :keyword, '%'))) ESCAPE '\\' OR "
          + "unaccent(LOWER(COALESCE(r.content ->> 'location', ''))) LIKE unaccent(LOWER(CONCAT('%', :keyword, '%'))) ESCAPE '\\' OR "
          + "unaccent(LOWER(COALESCE(r.content ->> 'industry', ''))) LIKE unaccent(LOWER(CONCAT('%', :keyword, '%'))) ESCAPE '\\') ";

  /**
   * Application totals for the given postings, keyed by job id. Callers must scope the input ids to
   * the projection they are authorized to expose (the public company roles endpoint passes only
   * publicly visible ids). Postings with no applications are absent from the result.
   */
  @Query(value = "SELECT job_id AS jobId, count(*) AS total FROM applications "
      + "WHERE job_id IN (:jobIds) GROUP BY job_id", nativeQuery = true)
  List<ApplicationCount> countApplicationsForJobs(@Param("jobIds") List<Long> jobIds);

  @Query(value = "SELECT job_id AS jobId, count(*) AS total FROM applications "
      + "WHERE company_id = :companyId AND job_id IN (:jobIds) GROUP BY job_id", nativeQuery = true)
  List<ApplicationCount> countApplications(@Param("companyId") Long companyId,
      @Param("jobIds") List<Long> jobIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select j from JobPosting j where j.companyId=:company and j.status='PUBLISHED' order by j.id")
  List<JobPosting> lockPublished(@Param("company") Long company);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select j from JobPosting j where j.id=:id and j.companyId=:company")
  Optional<JobPosting> lock(@Param("id") Long id, @Param("company") Long company);

  @Query(
      "select j from JobPosting j where j.status in ('PUBLISHED','PAUSED') and"
          + " j.approvedDeadline<=:now order by j.approvedDeadline,j.id")
  List<JobPosting> findDue(@Param("now") Instant now, Pageable page);

  @Query(
      value =
          "SELECT jp.id"
              + PUBLIC_POSTING_FROM
              + "AND NOT EXISTS (SELECT 1 FROM company_memberships cm "
              + "WHERE cm.user_id = CAST(:callerUserId AS bigint) "
              + "AND cm.company_id = jp.company_id AND cm.status = 'ACTIVE') "
              + PUBLIC_SEARCH_FILTER
              + "ORDER BY jp.created_at DESC, jp.id DESC",
      countQuery =
          "SELECT COUNT(*)"
              + PUBLIC_POSTING_FROM
              + "AND NOT EXISTS (SELECT 1 FROM company_memberships cm "
              + "WHERE cm.user_id = CAST(:callerUserId AS bigint) "
              + "AND cm.company_id = jp.company_id AND cm.status = 'ACTIVE') "
              + PUBLIC_SEARCH_FILTER,
      nativeQuery = true)
  org.springframework.data.domain.Page<Long> searchPublicJobIds(
      @Param("keyword") String keyword,
      @Param("location") String location,
      @Param("industry") String industry,
      @Param("employmentType") String employmentType,
      @Param("workArrangement") String workArrangement,
      @Param("salaryMin") java.math.BigDecimal salaryMin,
      @Param("salaryMax") java.math.BigDecimal salaryMax,
      @Param("deadlineFilter") Instant deadlineFilter,
      @Param("now") Instant now,
      @Param("callerUserId") Long callerUserId,
      org.springframework.data.domain.Pageable pageable);

  @Query(
      value =
          "SELECT c.id AS companyId, c.name AS companyName, COUNT(*) AS roleCount"
              + PUBLIC_POSTING_FROM
              + PUBLIC_COMPANY_SEARCH_FILTER
              + "GROUP BY c.id, c.name "
              + "ORDER BY COUNT(*) DESC, lower(c.name) ASC, c.id ASC",
      countQuery =
          "SELECT COUNT(*) FROM (SELECT c.id"
              + PUBLIC_POSTING_FROM
              + PUBLIC_COMPANY_SEARCH_FILTER
              + "GROUP BY c.id, c.name) grouped",
      nativeQuery = true)
  org.springframework.data.domain.Page<PublicCompanyGroup> searchPublicCompanyIds(
      @Param("keyword") String keyword,
      @Param("now") Instant now,
      org.springframework.data.domain.Pageable pageable);

  @Query(
      value =
          "SELECT jp.id"
              + PUBLIC_POSTING_FROM
              + "AND jp.company_id IN (:companyIds) "
              + "ORDER BY jp.company_id ASC, jp.created_at DESC, jp.id DESC",
      nativeQuery = true)
  List<Long> searchPublicJobIdsForCompanies(
      @Param("companyIds") List<Long> companyIds,
      @Param("now") Instant now);
}
