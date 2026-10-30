package com.guardwork.backend.job.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.job.dto.JobDetail;
import com.guardwork.backend.job.service.JobLifecycleService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/me/jobs/{id}")
public class JobLifecycleController {
  private final JobLifecycleService service;

  public JobLifecycleController(JobLifecycleService service) {
    this.service = service;
  }

  @PostMapping("/pause")
  public ApiResponse<JobDetail> pause(
      @AuthenticationPrincipal UserPrincipal actor,
      @PathVariable Long id,
      @RequestBody JobLifecycleService.Request request) {
    return ApiResponse.ok(service.change(actor.getId(), id, "pause", request));
  }

  @PostMapping("/resume")
  public ApiResponse<JobDetail> resume(
      @AuthenticationPrincipal UserPrincipal actor,
      @PathVariable Long id,
      @RequestBody JobLifecycleService.Request request) {
    return ApiResponse.ok(service.change(actor.getId(), id, "resume", request));
  }

  @PostMapping("/close")
  public ApiResponse<JobDetail> close(
      @AuthenticationPrincipal UserPrincipal actor,
      @PathVariable Long id,
      @RequestBody JobLifecycleService.Request request) {
    return ApiResponse.ok(service.change(actor.getId(), id, "close", request));
  }
}
