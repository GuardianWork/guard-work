package com.guardwork.backend.application.controller;

import com.guardwork.backend.application.dto.ApplicationFormResponse;
import com.guardwork.backend.application.dto.JobApplicationResponse;
import com.guardwork.backend.application.dto.SubmitApplicationRequest;
import com.guardwork.backend.application.service.JobApplicationService;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/candidate/applications")
@RequiredArgsConstructor
public class JobApplicationController {

    private final JobApplicationService applicationService;

    @GetMapping("/form")
    public ApiResponse<ApplicationFormResponse> form(@RequestParam("jobId") Long jobId) {
        return ApiResponse.ok(applicationService.form(SecurityUtils.getCurrentUserId(), jobId));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<JobApplicationResponse>> submit(
            @Valid @RequestBody SubmitApplicationRequest request) {
        JobApplicationResponse response = applicationService.submit(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Application submitted successfully", response));
    }
}
