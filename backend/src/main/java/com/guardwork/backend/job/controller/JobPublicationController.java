package com.guardwork.backend.job.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.job.service.JobPublicationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class JobPublicationController {
  private final JobPublicationService service;
  public JobPublicationController(JobPublicationService service){this.service=service;}
  @PutMapping("/api/admin/job-revisions/{id}/decision")
  public ApiResponse<JobPublicationService.Receipt> decide(@AuthenticationPrincipal UserPrincipal actor,@PathVariable Long id,
      @RequestBody JobPublicationService.Decision request,HttpServletRequest http) {
    return ApiResponse.ok(service.decide(actor.getId(),id,request,http.getRemoteAddr(),http.getHeader("User-Agent")));
  }
  @GetMapping("/api/jobs/{id}")
  public ApiResponse<JobPublicationService.PublicDetail> publicDetail(@PathVariable Long id){return ApiResponse.ok(service.publicDetail(id));}
}
