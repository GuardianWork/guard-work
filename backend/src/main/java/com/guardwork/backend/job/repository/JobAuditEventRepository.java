package com.guardwork.backend.job.repository;

import com.guardwork.backend.job.model.JobAuditEvent;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface JobAuditEventRepository extends JpaRepository<JobAuditEvent, Long> {
}
