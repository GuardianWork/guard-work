package com.guardwork.backend.job.repository;

import com.guardwork.backend.job.model.JdScanAttempt;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JdScanAttemptRepository extends JpaRepository<JdScanAttempt, Long> {
  Optional<JdScanAttempt> findByClaimToken(UUID token);
  List<JdScanAttempt> findByScanRunIdOrderByAttemptNumber(Long runId);
}
