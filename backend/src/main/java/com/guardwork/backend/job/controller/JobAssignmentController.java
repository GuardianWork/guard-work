package com.guardwork.backend.job.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.job.dto.JobDetail;
import com.guardwork.backend.job.service.JobAssignmentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/me/jobs/{id}")
public class JobAssignmentController {
  private final JobAssignmentService service;

  public JobAssignmentController(JobAssignmentService service) {
    this.service = service;
  }

  public record Version(Long expectedJobVersion) {
  }

  public record Responsibility(Long expectedJobVersion, Boolean responsible) {
  }

  @PutMapping("/assignments/{membershipId}")
  public ApiResponse<JobDetail> assign(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id,
      @PathVariable Long membershipId, @RequestBody Version request) {
    return ApiResponse.ok(service.assign(actor.getId(), id, membershipId, request.expectedJobVersion(), true));
  }

  @DeleteMapping("/assignments/{membershipId}")
  public ApiResponse<JobDetail> unassign(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id,
      @PathVariable Long membershipId, @RequestParam(required = false) Long expectedJobVersion) {
    return ApiResponse.ok(service.assign(actor.getId(), id, membershipId, expectedJobVersion, false));
  }

  @PutMapping("/owner-responsibility")
  public ApiResponse<JobDetail> responsibility(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id,
      @RequestBody Responsibility request) {
    return ApiResponse
        .ok(service.responsibility(actor.getId(), id, request.expectedJobVersion(), request.responsible()));
  }
}
