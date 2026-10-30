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
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select j from JobPosting j where j.id=:id and j.companyId=:company")
  Optional<JobPosting> lock(@Param("id") Long id, @Param("company") Long company);

  @Query(
      "select j from JobPosting j where j.status in ('PUBLISHED','PAUSED') and"
          + " j.approvedDeadline<=:now order by j.approvedDeadline,j.id")
  List<JobPosting> findDue(@Param("now") Instant now, Pageable page);
}
