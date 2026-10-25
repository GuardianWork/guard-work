package com.guardwork.backend.invitation.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.*;
import com.guardwork.backend.invitation.dto.*;
import com.guardwork.backend.invitation.service.OwnerInvitationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/me/invitations")
public class OwnerInvitationController {
  private final OwnerInvitationService service;

  public OwnerInvitationController(OwnerInvitationService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<OwnerInvitationResponse> issue(
      @AuthenticationPrincipal UserPrincipal actor, @RequestBody IssueInvitationRequest request) {
    return new ApiResponse<>(
        201, "Recruiter invitation issued", service.issue(actor.getId(), request));
  }

  @PostMapping("/{id}/resend")
  public ApiResponse<OwnerInvitationResponse> resend(
      @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") Long id) {
    return ApiResponse.ok(service.resend(actor.getId(), id));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<OwnerInvitationResponse> revoke(
      @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") Long id) {
    return ApiResponse.ok(service.revoke(actor.getId(), id));
  }

  @GetMapping
  public ApiResponse<PageResponse<OwnerInvitationResponse>> list(
      @AuthenticationPrincipal UserPrincipal actor,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    return ApiResponse.ok(service.list(actor.getId(), status, page, size));
  }
}
