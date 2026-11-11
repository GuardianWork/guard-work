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

  /**
   * Counters of the recipient inbox in one pass: the highest id (the client
   * watermark), the row count, and the rows without a read receipt.
   */
  @Query(
      value =
          """
          SELECT max(id) AS "latestId", count(*) AS "total",
                 count(*) FILTER (WHERE read_at IS NULL) AS "unread"
          FROM notification_inbox
          WHERE recipient_user_id = :recipient
          """,
      nativeQuery = true)
  NotificationInboxAggregate aggregateByRecipient(@Param("recipient") Long recipient);

  /**
   * Audience-scoped counterpart of {@link #aggregateByRecipient(Long)}.
   *
   * <p>The audience is bound as its wire value: a native query has no entity
   * mapping for the enum and would bind its ordinal instead.
   */
  @Query(
      value =
          """
          SELECT max(id) AS "latestId", count(*) AS "total",
                 count(*) FILTER (WHERE read_at IS NULL) AS "unread"
          FROM notification_inbox
          WHERE recipient_user_id = :recipient AND audience = :audience
          """,
      nativeQuery = true)
  NotificationInboxAggregate aggregateByRecipientAndAudience(
      @Param("recipient") Long recipient, @Param("audience") String audience);

  /** Rows newer than the watermark, newest first; no count query is executed. */
  @Query(
      "select n from NotificationInbox n where n.recipientUserId = :recipient and n.id > :sinceId order by n.id desc")
  List<NotificationInbox> findDeltaByRecipient(
      @Param("recipient") Long recipient, @Param("sinceId") Long sinceId, Pageable pageable);

  /** Audience-scoped counterpart of {@link #findDeltaByRecipient(Long, Long, Pageable)}. */
  @Query(
      "select n from NotificationInbox n where n.recipientUserId = :recipient and n.audience = :audience and n.id > :sinceId order by n.id desc")
  List<NotificationInbox> findDeltaByRecipientAndAudience(
      @Param("recipient") Long recipient,
      @Param("audience") NotificationAudience audience,
      @Param("sinceId") Long sinceId,
      Pageable pageable);

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
