package com.guardwork.backend.notification.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.*;
import com.guardwork.backend.notification.dto.*;
import com.guardwork.backend.notification.service.NotificationInboxService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me/notifications")
public class NotificationController {
  private final NotificationInboxService inbox;

  public NotificationController(NotificationInboxService inbox) {
    this.inbox = inbox;
  }

  @GetMapping
  public ApiResponse<PageResponse<NotificationResponse>> list(
      @AuthenticationPrincipal UserPrincipal actor,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    return ApiResponse.ok(inbox.list(actor.getId(), page, size));
  }

  @PatchMapping("/{id}/read")
  public ApiResponse<NotificationReadResponse> read(
      @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") Long id) {
    return ApiResponse.ok(inbox.read(actor.getId(), id));
  }
}
