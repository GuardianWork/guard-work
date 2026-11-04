package com.guardwork.backend.application.controller;

import com.guardwork.backend.application.dto.JobApplicationResponse;
import com.guardwork.backend.application.dto.ApplicationDetailResponse;
import com.guardwork.backend.application.dto.JobApplicationSummaryResponse;
import com.guardwork.backend.application.dto.SubmitApplicationRequest;
import com.guardwork.backend.application.service.JobApplicationService;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/candidate/applications")
@RequiredArgsConstructor
public class JobApplicationController {

    private final JobApplicationService applicationService;

    @GetMapping
    public ApiResponse<List<JobApplicationSummaryResponse>> list() {
        return ApiResponse.ok(applicationService.list(SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/{applicationId}")
    public ResponseEntity<ApiResponse<ApplicationDetailResponse>> detail(
            @org.springframework.web.bind.annotation.PathVariable UUID applicationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
                applicationService.candidateDetail(SecurityUtils.getCurrentUserId(), applicationId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<JobApplicationResponse>> submit(
            @Valid @RequestBody SubmitApplicationRequest request) {
        JobApplicationResponse response = applicationService.submit(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Application submitted successfully", response));
    }
}
