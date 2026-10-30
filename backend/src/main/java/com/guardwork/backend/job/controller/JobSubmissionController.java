package com.guardwork.backend.job.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.job.dto.JobDetail;
import com.guardwork.backend.job.service.JobSubmissionService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/me/jobs/{jobId}")
public class JobSubmissionController {
  private final JobSubmissionService service;

  public JobSubmissionController(JobSubmissionService service) {
    this.service = service;
  }

  @GetMapping("/draft/preview")
  public ApiResponse<JobSubmissionService.Preview> preview(
      @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long jobId) {
    return ApiResponse.ok(service.preview(actor.getId(), jobId));
  }

  @PostMapping("/revisions/{revisionId}/submit")
  public ApiResponse<JobDetail> submit(
      @AuthenticationPrincipal UserPrincipal actor,
      @PathVariable Long jobId,
      @PathVariable Long revisionId,
      @RequestBody JobSubmissionService.Confirmation request) {
    return ApiResponse.ok(service.submit(actor.getId(), jobId, revisionId, request));
  }

  @PostMapping("/revisions/{revisionId}/withdraw")
  public ApiResponse<JobDetail> withdraw(
      @AuthenticationPrincipal UserPrincipal actor,
      @PathVariable Long jobId,
      @PathVariable Long revisionId,
      @RequestBody JobSubmissionService.Confirmation request) {
    return ApiResponse.ok(service.withdraw(actor.getId(), jobId, revisionId, request));
  }
}
