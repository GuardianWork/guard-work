package com.guardwork.backend.job.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.job.service.JdScanService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class JdScanController {
  private final JdScanService service;
  public JdScanController(JdScanService service) { this.service = service; }

  @PostMapping("/api/companies/me/jobs/{jobId}/revisions/{revisionId}/scans")
  public ApiResponse<JdScanService.View> request(@AuthenticationPrincipal UserPrincipal actor,
      @PathVariable Long jobId, @PathVariable Long revisionId, @RequestBody JdScanService.Request request) {
    return ApiResponse.ok(service.request(actor.getId(), jobId, revisionId, request));
  }

  @GetMapping("/api/companies/me/jobs/{jobId}/revisions/{revisionId}/scans/{scanId}")
  public ApiResponse<JdScanService.View> inspect(@AuthenticationPrincipal UserPrincipal actor,
      @PathVariable Long jobId, @PathVariable Long revisionId, @PathVariable Long scanId) {
    return ApiResponse.ok(service.inspect(actor.getId(), jobId, revisionId, scanId));
  }

  @GetMapping("/api/staff/job-revisions/{revisionId}/scans/{scanId}")
  public ApiResponse<JdScanService.View> staffInspect(@AuthenticationPrincipal UserPrincipal actor,
      @PathVariable Long revisionId, @PathVariable Long scanId) {
    return ApiResponse.ok(service.staffInspect(actor.getId(), revisionId, scanId));
  }
}
