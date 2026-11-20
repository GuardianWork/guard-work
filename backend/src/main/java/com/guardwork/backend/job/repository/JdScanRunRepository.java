package com.guardwork.backend.job.repository;

import com.guardwork.backend.job.model.JdScanRun;
import java.time.*;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface JdScanRunRepository extends JpaRepository<JdScanRun, Long> {
  Optional<JdScanRun> findByCompanyIdAndInputHashAndProfileAndLegalSnapshotAndModeAndAsOfDate(
      Long companyId, String hash, String profile, String snapshot, String mode, LocalDate date);

  @Query(value = "SELECT * FROM jd_scan_runs WHERE "
      + "(status='QUEUED' AND available_at<=:now) OR (status='RUNNING' AND lease_until<=:now) "
      + "ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
  Optional<JdScanRun> claimable(@Param("now") Instant now);

  @Query(value = "SELECT * FROM jd_scan_runs WHERE id=:id FOR UPDATE", nativeQuery = true)
  Optional<JdScanRun> lock(@Param("id") Long id);
}
