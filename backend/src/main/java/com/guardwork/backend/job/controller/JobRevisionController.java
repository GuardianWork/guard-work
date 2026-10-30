package com.guardwork.backend.job.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.*;
import com.guardwork.backend.job.dto.JobDetail;
import com.guardwork.backend.job.service.JobRevisionService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/me/jobs/{jobId}")
public class JobRevisionController {
  private final JobRevisionService service;
  public JobRevisionController(JobRevisionService service){this.service=service;}
  @PostMapping("/draft")
  public ApiResponse<JobDetail> derive(@AuthenticationPrincipal UserPrincipal actor,@PathVariable Long jobId,@RequestBody JobRevisionService.Derive request) {
    return ApiResponse.ok(service.derive(actor.getId(),jobId,request));
  }
  @GetMapping("/revisions")
  public ApiResponse<PageResponse<JobRevisionService.History>> history(@AuthenticationPrincipal UserPrincipal actor,@PathVariable Long jobId,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
    return ApiResponse.ok(service.history(actor.getId(),jobId,page,size));
  }
}
