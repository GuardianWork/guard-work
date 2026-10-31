package com.guardwork.backend.staff.repository;

import com.guardwork.backend.staff.model.AccountBan;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AccountBanRepository extends JpaRepository<AccountBan,Long> {
  Optional<AccountBan> findByUserIdAndEndedAtIsNull(Long userId);
  Optional<AccountBan> findByCompanyIdAndEndedAtIsNull(Long companyId);
  @Query("select b from AccountBan b where b.endedAt is null and b.expiresAt<=:now order by b.id")
  List<AccountBan> findDue(@Param("now") Instant now, Pageable page);
}
