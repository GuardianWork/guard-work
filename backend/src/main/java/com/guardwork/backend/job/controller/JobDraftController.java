package com.guardwork.backend.job.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.*;
import com.guardwork.backend.job.dto.*;
import com.guardwork.backend.job.service.JobDraftService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/me/jobs")
public class JobDraftController {
  private final JobDraftService service;
  public JobDraftController(JobDraftService service) { this.service = service; }

  @PostMapping
  public ResponseEntity<ApiResponse<JobDetail>> create(@AuthenticationPrincipal UserPrincipal actor,
      @RequestBody JobRequests.Create request) {
    return ResponseEntity.status(201).body(new ApiResponse<>(201, "Created", service.create(actor.getId(), request)));
  }
  @GetMapping
  public ApiResponse<PageResponse<JobDetail>> list(@AuthenticationPrincipal UserPrincipal actor,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String status, @RequestParam(required = false) Boolean needsReassignment) {
    return ApiResponse.ok(service.list(actor.getId(), page, size, status, needsReassignment));
  }
  @GetMapping("/{id}")
  public ApiResponse<JobDetail> inspect(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
    return ApiResponse.ok(service.inspect(actor.getId(), id));
  }
  @PutMapping("/{id}/draft")
  public ApiResponse<JobDetail> save(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id,
      @RequestBody JobRequests.Save request) {
    return ApiResponse.ok(service.save(actor.getId(), id, request));
  }
}
