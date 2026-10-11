package com.guardwork.backend.notification.repository;

import com.guardwork.backend.notification.model.NotificationOutbox;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select n from NotificationOutbox n where n.eventId=:id")
  Optional<NotificationOutbox> findForUpdate(@Param("id") UUID id);

  @Query(
      "select n.eventId from NotificationOutbox n where n.deliveredAt is null and n.nextAttemptAt"
          + " <= :now order by n.nextAttemptAt,n.eventId")
  List<UUID> findDue(@Param("now") Instant now, Pageable page);
}
