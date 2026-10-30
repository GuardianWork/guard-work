package com.guardwork.backend.job.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.*;
import com.guardwork.backend.job.service.JobReviewQueueService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/staff")
public class JobReviewQueueController {
  private final JobReviewQueueService service;

  public JobReviewQueueController(JobReviewQueueService service) {
    this.service = service;
  }

  @GetMapping("/job-revisions")
  public ApiResponse<PageResponse<JobReviewQueueService.Review>> queue(
      @AuthenticationPrincipal UserPrincipal actor,
      @RequestParam(defaultValue = "PENDING_REVIEW") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(service.queue(actor.getId(), status, page, size));
  }

  @GetMapping("/job-revisions/{id}")
  public ApiResponse<JobReviewQueueService.Review> inspect(
      @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
    return ApiResponse.ok(service.inspect(actor.getId(), id));
  }

  @GetMapping("/jobs/{jobId}/revisions")
  public ApiResponse<PageResponse<JobReviewQueueService.Review>> history(
      @AuthenticationPrincipal UserPrincipal actor,
      @PathVariable Long jobId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(service.history(actor.getId(), jobId, page, size));
  }
}
