package com.guardwork.backend.membership.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.*;
import com.guardwork.backend.membership.dto.*;
import com.guardwork.backend.membership.service.MembershipManagementService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/me")
public class MembershipManagementController {
  private final MembershipManagementService service;

  public MembershipManagementController(MembershipManagementService service) {
    this.service = service;
  }

  @GetMapping("/members")
  public ApiResponse<PageResponse<MemberResponse>> list(
      @AuthenticationPrincipal UserPrincipal actor,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    return ApiResponse.ok(service.list(actor.getId(), page, size));
  }

  @DeleteMapping("/members/{id}")
  public ApiResponse<MembershipEndResponse> remove(
      @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") Long id) {
    return ApiResponse.ok(service.remove(actor.getId(), id));
  }

  @PostMapping("/membership/leave")
  public ApiResponse<MembershipEndResponse> leave(@AuthenticationPrincipal UserPrincipal actor) {
    return ApiResponse.ok(service.leave(actor.getId()));
  }
}
