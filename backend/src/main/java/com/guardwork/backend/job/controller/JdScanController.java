package com.guardwork.backend.job.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.job.service.JdScanService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class JdScanController {
  private final JdScanService service;
  public JdScanController(JdScanService service) { this.service = service; }

  @PostMapping("/api/jobs/{jobId}/revisions/{revisionId}/scans")
  public ApiResponse<JdScanService.Result> requestShared(@AuthenticationPrincipal UserPrincipal actor,
      @PathVariable Long jobId, @PathVariable Long revisionId, @RequestBody JdScanService.Request request,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return ApiResponse.ok(service.requestShared(actor.getId(), jobId, revisionId, request));
  }

  @GetMapping("/api/jobs/{jobId}/revisions/{revisionId}/scans/latest")
  public ApiResponse<JdScanService.Result> latestShared(@AuthenticationPrincipal UserPrincipal actor,
      @PathVariable Long jobId, @PathVariable Long revisionId,
      @RequestParam(required = false) Long expectedRevisionVersion, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return ApiResponse.ok(service.latestShared(actor.getId(), jobId, revisionId, expectedRevisionVersion));
  }

  @PostMapping("/api/companies/me/jobs/{jobId}/revisions/{revisionId}/scans")
  public ApiResponse<JdScanService.View> request(@AuthenticationPrincipal UserPrincipal actor,
      @PathVariable Long jobId, @PathVariable Long revisionId, @RequestBody JdScanService.Request request) {
    return ApiResponse.ok(service.request(actor.getId(), jobId, revisionId, request));
  }

  @GetMapping("/api/companies/me/jobs/{jobId}/revisions/{revisionId}/scans/latest")
  public ApiResponse<JdScanService.View> latest(@AuthenticationPrincipal UserPrincipal actor,
      @PathVariable Long jobId, @PathVariable Long revisionId,
      @RequestParam(required = false) Long expectedRevisionVersion, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return ApiResponse.ok(service.latest(actor.getId(), jobId, revisionId, expectedRevisionVersion));
  }

  @GetMapping("/api/staff/job-revisions/{revisionId}/scans/latest")
  public ApiResponse<JdScanService.View> staffLatest(@AuthenticationPrincipal UserPrincipal actor,
      @PathVariable Long revisionId, @RequestParam(required = false) Long expectedRevisionVersion,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return ApiResponse.ok(service.staffLatest(actor.getId(), revisionId, expectedRevisionVersion));
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
