package com.guardwork.backend.notification.repository;

import com.guardwork.backend.notification.model.UserNotification;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {
  boolean existsBySourceEventId(UUID id);

  Page<UserNotification> findByRecipientUserId(Long recipient, Pageable page);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select n from UserNotification n where n.id=:id and n.recipientUserId=:actor")
  Optional<UserNotification> findRecipientForUpdate(
      @Param("id") Long id, @Param("actor") Long actor);
}
