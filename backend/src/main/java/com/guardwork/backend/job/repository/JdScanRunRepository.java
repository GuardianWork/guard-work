package com.guardwork.backend.job.repository;

import com.guardwork.backend.job.model.JdScanRun;
import java.time.*;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface JdScanRunRepository extends JpaRepository<JdScanRun, Long> {
  // The caller holds the Company lock, shared by every scan creation route.
  @Query(value = "SELECT * FROM jd_scan_runs WHERE company_id=:companyId AND input_hash=:inputHash "
      + "ORDER BY CASE WHEN status='COMPLETED' THEN 0 WHEN status IN ('QUEUED','RUNNING') THEN 1 ELSE 2 END, "
      + "finished_at DESC NULLS LAST,id DESC LIMIT 1", nativeQuery = true)
  Optional<JdScanRun> reusableForContent(@Param("companyId") Long companyId,
      @Param("inputHash") String inputHash);

  @Query(value = "SELECT r.* FROM jd_scan_runs r WHERE r.company_id=:companyId "
      + "AND r.input_hash=:inputHash AND r.status='COMPLETED' "
      + "AND EXISTS (SELECT 1 FROM jd_revision_scans s WHERE s.scan_run_id=r.id "
      + "AND s.company_id=:companyId AND s.job_id=:jobId AND s.revision_id=:revisionId) "
      + "ORDER BY r.finished_at DESC,r.id DESC LIMIT 1", nativeQuery = true)
  Optional<JdScanRun> latestCompletedForRevision(@Param("companyId") Long companyId,
      @Param("jobId") Long jobId, @Param("revisionId") Long revisionId,
      @Param("inputHash") String inputHash);

  @Query(value = "SELECT * FROM jd_scan_runs WHERE "
      + "(status='QUEUED' AND available_at<=:now) OR (status='RUNNING' AND lease_until<=:now) "
      + "ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
  Optional<JdScanRun> claimable(@Param("now") Instant now);

  @Query(value = "SELECT * FROM jd_scan_runs WHERE id=:id FOR UPDATE", nativeQuery = true)
  Optional<JdScanRun> lock(@Param("id") Long id);
}
