package com.guardwork.backend.job.repository;

import com.guardwork.backend.job.model.JobRevision;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface JobRevisionRepository extends JpaRepository<JobRevision, Long> {
  Optional<JobRevision> findByJobIdAndStateIn(Long jobId, Collection<String> states);
}
