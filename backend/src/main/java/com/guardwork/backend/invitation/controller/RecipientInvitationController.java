package com.guardwork.backend.invitation.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.invitation.dto.*;
import com.guardwork.backend.invitation.service.RecipientInvitationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/company-invitations")
public class RecipientInvitationController {
  private final RecipientInvitationService service;

  public RecipientInvitationController(RecipientInvitationService service) {
    this.service = service;
  }

  @PostMapping("/preview")
  public ApiResponse<InvitationPreviewResponse> preview(
      @AuthenticationPrincipal UserPrincipal actor,
      @RequestBody RecipientInvitationRequest request) {
    return ApiResponse.ok(service.preview(actor.getId(), request));
  }

  @PostMapping("/accept")
  public ResponseEntity<ApiResponse<InvitationAcceptanceResponse>> accept(
      @AuthenticationPrincipal UserPrincipal actor,
      @RequestBody RecipientInvitationRequest request) {
    var result = service.accept(actor.getId(), request);
    int status = result.created() ? 201 : 200;
    return ResponseEntity.status(status)
        .body(new ApiResponse<>(status, "Recruiter invitation accepted", result.receipt()));
  }

  @PostMapping("/decline")
  public ApiResponse<InvitationDeclineResponse> decline(
      @AuthenticationPrincipal UserPrincipal actor,
      @RequestBody RecipientInvitationRequest request) {
    return ApiResponse.ok(service.decline(actor.getId(), request));
  }
}
