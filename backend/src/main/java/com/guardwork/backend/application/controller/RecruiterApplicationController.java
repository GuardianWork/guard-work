package com.guardwork.backend.application.controller;

import com.guardwork.backend.application.dto.RecruiterApplicationSummaryResponse;
import com.guardwork.backend.application.service.JobApplicationService;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.common.utils.SecurityUtils;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/me/jobs/{jobId}/applications")
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class RecruiterApplicationController {

    JobApplicationService applications;

    @GetMapping
    public ApiResponse<PageResponse<RecruiterApplicationSummaryResponse>> list(
            @PathVariable Long jobId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok("Applications retrieved successfully",
                applications.listForCompany(SecurityUtils.getCurrentUserId(), jobId, search, status, page, size));
    }
}
