package com.guardwork.backend.notification.service;

import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.membership.service.CompanyAccessService;
import com.guardwork.backend.notification.dto.*;
import com.guardwork.backend.notification.repository.UserNotificationRepository;
import java.time.Clock;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class NotificationInboxService {
  private final CompanyAccessService access;
  private final UserNotificationRepository inbox;
  private final Clock clock;

  public NotificationInboxService(
      CompanyAccessService access, UserNotificationRepository inbox, Clock clock) {
    this.access = access;
    this.inbox = inbox;
    this.clock = clock;
  }

  public PageResponse<NotificationResponse> list(Long actor, int page, int size) {
    access.requireActiveUser(actor);
    if (page < 0 || size < 1 || size > 100)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Invalid pagination");
    var result =
        inbox.findByRecipientUserId(
            actor, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
    return PageResponse.of(
        result.getContent().stream().map(NotificationResponse::from).toList(),
        page,
        size,
        result.getTotalElements());
  }

  public NotificationReadResponse read(Long actor, Long id) {
    access.requireActiveUser(actor);
    var item =
        inbox
            .findRecipientForUpdate(id, actor)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "40400 NOTIFICATION_NOT_FOUND: Notification not found"));
    if (item.getReadAt() == null) item.setReadAt(clock.instant());
    return new NotificationReadResponse(item.getId(), item.getReadAt());
  }
}
