package com.guardwork.backend.notification.service;

import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.common.exception.AppException;
import com.guardwork.backend.common.exception.ErrorCode;
import com.guardwork.backend.common.utils.PaginationUtils;
import com.guardwork.backend.membership.service.CompanyAccessService;
import com.guardwork.backend.notification.dto.*;
import com.guardwork.backend.notification.model.NotificationAudience;
import com.guardwork.backend.notification.model.NotificationCategory;
import com.guardwork.backend.notification.model.NotificationEventType;
import com.guardwork.backend.notification.model.NotificationInbox;
import com.guardwork.backend.notification.repository.NotificationInboxRepository;
import java.time.Clock;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class NotificationInboxService {
  CompanyAccessService access;
  NotificationInboxRepository inbox;
  Clock clock;

  public PageResponse<NotificationResponse> list(Long actor, Pageable pageable) {
    return list(actor, pageable, null, null, null, null, null);
  }

  public PageResponse<NotificationResponse> list(
      Long actor,
      Pageable pageable,
      Boolean read,
      String category,
      String eventType,
      Long sinceId,
      String audience) {
    access.requireActiveUser(actor);
    String categoryWire = resolveCategory(category);
    NotificationEventType event = resolveEventType(eventType);
    NotificationAudience audienceValue = resolveAudience(audience);
    if (sinceId != null && sinceId < 0) {
      throw new AppException(ErrorCode.INVALID_INPUT);
    }
    Pageable bounded =
        PaginationUtils.validateAndBound(
            PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "id")));
    Specification<NotificationInbox> filter =
        (root, query, cb) -> {
          var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
          predicates.add(cb.equal(root.get("recipientUserId"), actor));
          if (audienceValue != null) {
            predicates.add(cb.equal(root.get("audience"), audienceValue));
          }
          if (read != null) {
            predicates.add(read ? cb.isNotNull(root.get("readAt")) : cb.isNull(root.get("readAt")));
          }
          if (categoryWire != null) {
            predicates.add(cb.equal(root.get("category"), categoryWire));
          }
          if (event != null) {
            predicates.add(cb.equal(root.get("eventType"), event));
          }
          if (sinceId != null) {
            predicates.add(cb.greaterThan(root.get("id"), sinceId));
          }
          return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    var result = inbox.findAll(filter, bounded);
    return PaginationUtils.toPageResponse(result, NotificationResponse::from);
  }

  public NotificationResponse detail(Long actor, Long id) {
    return detail(actor, id, null);
  }

  public NotificationResponse detail(Long actor, Long id, String audience) {
    access.requireActiveUser(actor);
    NotificationAudience audienceValue = resolveAudience(audience);
    var found =
        audienceValue == null
            ? inbox.findByRecipientUserIdAndId(actor, id)
            : inbox.findByRecipientUserIdAndAudienceAndId(actor, audienceValue, id);
    var item =
        found.orElseThrow(() -> new AppException(ErrorCode.NOTIFICATION_NOT_FOUND));
    return NotificationResponse.from(item);
  }

  public NotificationUnreadCountResponse unreadCount(Long actor) {
    return unreadCount(actor, null);
  }

  public NotificationUnreadCountResponse unreadCount(Long actor, String audience) {
    access.requireActiveUser(actor);
    NotificationAudience audienceValue = resolveAudience(audience);
    long count =
        audienceValue == null
            ? inbox.countByRecipientUserIdAndReadAtIsNull(actor)
            : inbox.countByRecipientUserIdAndAudienceAndReadAtIsNull(actor, audienceValue);
    return NotificationUnreadCountResponse.builder().unreadCount(count).build();
  }

  public NotificationMarkAllReadResponse markAllRead(Long actor) {
    return markAllRead(actor, null);
  }

  public NotificationMarkAllReadResponse markAllRead(Long actor, String audience) {
    access.requireActiveUser(actor);
    NotificationAudience audienceValue = resolveAudience(audience);
    var now = clock.instant();
    int updated =
        audienceValue == null
            ? inbox.markAllUnreadAsRead(actor, now)
            : inbox.markAllUnreadAsRead(actor, audienceValue, now);
    return NotificationMarkAllReadResponse.builder()
        .updatedCount((long) updated)
        .readAt(now)
        .build();
  }

  private static String resolveCategory(String category) {
    if (category == null || category.isBlank()) return null;
    String normalized = category.trim().toLowerCase();
    for (NotificationCategory c : NotificationCategory.values()) {
      if (c.wireValue().equals(normalized)) return c.wireValue();
    }
    throw new AppException(ErrorCode.INVALID_INPUT);
  }

  private static NotificationEventType resolveEventType(String eventType) {
    if (eventType == null || eventType.isBlank()) return null;
    String normalized = eventType.trim().toUpperCase();
    for (NotificationEventType t : NotificationEventType.values()) {
      if (t.name().equals(normalized)) return t;
    }
    throw new AppException(ErrorCode.INVALID_INPUT);
  }

  private static NotificationAudience resolveAudience(String audience) {
    if (audience == null || audience.isBlank()) return null;
    NotificationAudience value = NotificationAudience.fromWire(audience);
    if (value == null) throw new AppException(ErrorCode.INVALID_INPUT);
    return value;
  }

  public NotificationReadResponse read(Long actor, Long id) {
    return read(actor, id, null);
  }

  public NotificationReadResponse read(Long actor, Long id, String audience) {
    access.requireActiveUser(actor);
    NotificationAudience audienceValue = resolveAudience(audience);
    var item =
        inbox
            .findRecipientForUpdate(id, actor)
            .orElseThrow(() -> new AppException(ErrorCode.NOTIFICATION_NOT_FOUND));
    if (audienceValue != null && item.getAudience() != audienceValue) {
      throw new AppException(ErrorCode.NOTIFICATION_NOT_FOUND);
    }
    if (item.getReadAt() == null) item.setReadAt(clock.instant());
    return NotificationReadResponse.builder().id(item.getId()).readAt(item.getReadAt()).build();
  }
}
