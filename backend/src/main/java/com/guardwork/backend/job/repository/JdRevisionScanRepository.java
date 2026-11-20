package com.guardwork.backend.job.repository;

import com.guardwork.backend.job.model.JdRevisionScan;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JdRevisionScanRepository extends JpaRepository<JdRevisionScan, Long> {
  Optional<JdRevisionScan> findFirstByRevisionIdAndScanRunIdOrderByIdDesc(Long revisionId, Long scanId);
  Optional<JdRevisionScan> findByRevisionIdAndRevisionVersionAndScanRunId(Long revisionId, Long version, Long scanId);
}
