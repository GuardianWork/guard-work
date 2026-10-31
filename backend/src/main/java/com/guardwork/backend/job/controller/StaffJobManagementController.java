package com.guardwork.backend.job.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.job.service.StaffJobManagementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/staff/jobs")
public class StaffJobManagementController {
  private final StaffJobManagementService service;

  public StaffJobManagementController(StaffJobManagementService service) {
    this.service = service;
  }

  @PutMapping("/{jobId}/hide")
  public ApiResponse<StaffJobManagementService.HideReceipt> hide(
      @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long jobId,
      @RequestBody StaffJobManagementService.HideRequest request, HttpServletRequest http) {
    return ApiResponse.ok(service.hide(actor.getId(), jobId, request,
        http.getRemoteAddr(), http.getHeader("User-Agent")));
  }

  @GetMapping("/{jobId}/history")
  public ApiResponse<PageResponse<StaffJobManagementService.HistoryEvent>> history(
      @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long jobId,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(service.history(actor.getId(), jobId, page, size));
  }
}
