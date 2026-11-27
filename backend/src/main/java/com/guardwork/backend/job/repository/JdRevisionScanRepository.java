package com.guardwork.backend.job.repository;

import com.guardwork.backend.job.model.JdRevisionScan;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JdRevisionScanRepository extends JpaRepository<JdRevisionScan, Long> {
  @Query(value = "SELECT l.* FROM jd_revision_scans l JOIN jd_scan_runs r ON r.id=l.scan_run_id "
      + "WHERE l.company_id=:companyId AND r.company_id=:companyId AND l.job_id=:jobId "
      + "AND l.revision_id=:revisionId AND r.input_hash=:inputHash ORDER BY l.id DESC LIMIT 1",
      nativeQuery = true)
  Optional<JdRevisionScan> latestForContent(@Param("companyId") Long companyId,
      @Param("jobId") Long jobId, @Param("revisionId") Long revisionId,
      @Param("inputHash") String inputHash);

  Optional<JdRevisionScan> findFirstByRevisionIdAndScanRunIdOrderByIdDesc(Long revisionId, Long scanId);
  Optional<JdRevisionScan> findByRevisionIdAndRevisionVersionAndScanRunId(Long revisionId, Long version, Long scanId);
}
