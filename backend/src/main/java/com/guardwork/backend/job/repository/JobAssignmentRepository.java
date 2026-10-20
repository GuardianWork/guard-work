package com.guardwork.backend.job.repository;

import com.guardwork.backend.job.model.JobAssignment;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface JobAssignmentRepository extends JpaRepository<JobAssignment, Long> {
  List<JobAssignment> findByMembershipIdAndEndedAtIsNullOrderByJobId(Long membershipId);
  List<JobAssignment> findByJobIdAndMembershipIdAndEndedAtIsNull(Long jobId, Long membershipId);

  @Query("""
      select a from JobAssignment a, CompanyMembership m, User u
      where a.jobId=:job and a.endedAt is null and a.membershipId=m.id
      and m.companyId=a.companyId and m.joinedAt=a.membershipJoinedAt
      and m.status='ACTIVE' and m.role='RECRUITER' and u.id=m.userId and u.status='ACTIVE'
      order by a.id
      """)
  List<JobAssignment> current(@Param("job") Long job);
}
