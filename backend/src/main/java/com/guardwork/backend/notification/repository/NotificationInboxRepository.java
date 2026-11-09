package com.guardwork.backend.notification.repository;

import com.guardwork.backend.notification.model.NotificationAudience;
import com.guardwork.backend.notification.model.NotificationInbox;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface NotificationInboxRepository
    extends JpaRepository<NotificationInbox, Long>,
        JpaSpecificationExecutor<NotificationInbox> {
  boolean existsBySourceEventId(UUID id);

  Page<NotificationInbox> findByRecipientUserId(Long recipient, Pageable page);

  Optional<NotificationInbox> findByRecipientUserIdAndId(Long recipient, Long id);

  Optional<NotificationInbox> findByRecipientUserIdAndAudienceAndId(
      Long recipient, NotificationAudience audience, Long id);

  long countByRecipientUserIdAndReadAtIsNull(Long recipient);

  long countByRecipientUserIdAndAudienceAndReadAtIsNull(
      Long recipient, NotificationAudience audience);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select n from NotificationInbox n where n.id=:id and n.recipientUserId=:actor")
  Optional<NotificationInbox> findRecipientForUpdate(
      @Param("id") Long id, @Param("actor") Long actor);

  @Modifying
  @Query("update NotificationInbox n set n.readAt=:readAt where n.recipientUserId=:actor and n.readAt is null")
  int markAllUnreadAsRead(@Param("actor") Long actor, @Param("readAt") java.time.Instant readAt);

  @Modifying
  @Query("update NotificationInbox n set n.readAt=:readAt where n.recipientUserId=:actor and n.audience=:audience and n.readAt is null")
  int markAllUnreadAsRead(
      @Param("actor") Long actor,
      @Param("audience") NotificationAudience audience,
      @Param("readAt") java.time.Instant readAt);
}
