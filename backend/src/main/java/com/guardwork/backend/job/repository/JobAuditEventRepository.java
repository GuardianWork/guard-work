package com.guardwork.backend.job.repository;

import com.guardwork.backend.job.model.JobAuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobAuditEventRepository extends JpaRepository<JobAuditEvent, Long> {
  Page<JobAuditEvent> findByJobId(Long jobId, Pageable pageable);
}
