package com.guardwork.backend.notification.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.*;
import com.guardwork.backend.common.utils.PaginationUtils;
import com.guardwork.backend.notification.dto.*;
import com.guardwork.backend.notification.service.NotificationInboxService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me/notifications")
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class NotificationController {
  NotificationInboxService inbox;

  @GetMapping
  public ApiResponse<PageResponse<NotificationResponse>> list(
      @AuthenticationPrincipal UserPrincipal actor,
      @PageableDefault(size = PaginationUtils.DEFAULT_PAGE_SIZE) Pageable pageable,
      @RequestParam(required = false) Boolean read,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String eventType,
      @RequestParam(required = false) Long sinceId,
      @RequestParam(required = false) String audience) {
    return ApiResponse.ok(
        inbox.list(actor.getId(), pageable, read, category, eventType, sinceId, audience));
  }

  /** Unread total for badge counts; literal path wins over {@code GET /{id}}. */
  @GetMapping("/unread-count")
  public ApiResponse<NotificationUnreadCountResponse> unreadCount(
      @AuthenticationPrincipal UserPrincipal actor,
      @RequestParam(required = false) String audience) {
    return ApiResponse.ok(inbox.unreadCount(actor.getId(), audience));
  }

  @GetMapping("/{id}")
  public ApiResponse<NotificationResponse> detail(
      @AuthenticationPrincipal UserPrincipal actor,
      @PathVariable("id") Long id,
      @RequestParam(required = false) String audience) {
    return ApiResponse.ok(inbox.detail(actor.getId(), id, audience));
  }

  /**
   * One polling round: the rows newer than the client watermark and the counters of
   * the inbox. The literal path wins over {@code GET /{id}}.
   *
   * <p>The response carries an entity tag; a client that sends it back unchanged
   * receives {@code 304 Not Modified} instead of an unchanged body.
   */
  @GetMapping("/sync")
  public ResponseEntity<ApiResponse<NotificationSyncResponse>> sync(
      @AuthenticationPrincipal UserPrincipal actor,
      @RequestParam(required = false) String audience,
      @RequestParam(required = false) Long sinceId,
      @RequestParam(required = false) Integer limit,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    NotificationSyncResponse result = inbox.sync(actor.getId(), audience, sinceId, limit);
    String etag = inbox.syncEtag(actor.getId(), audience, result);
    if (etag.equals(ifNoneMatch)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
    }
    return ResponseEntity.ok().eTag(etag).body(ApiResponse.ok(result));
  }

  /** Marks every unread row read in one transaction; replaces per-item fan-out. */
  @PatchMapping("/read")
  public ApiResponse<NotificationMarkAllReadResponse> markAllRead(
      @AuthenticationPrincipal UserPrincipal actor,
      @RequestParam(required = false) String audience) {
    return ApiResponse.ok(inbox.markAllRead(actor.getId(), audience));
  }

  @PatchMapping("/{id}/read")
  public ApiResponse<NotificationReadResponse> read(
      @AuthenticationPrincipal UserPrincipal actor,
      @PathVariable("id") Long id,
      @RequestParam(required = false) String audience) {
    return ApiResponse.ok(inbox.read(actor.getId(), id, audience));
  }
}
