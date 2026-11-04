package com.guardwork.backend.application.controller;

import com.guardwork.backend.application.dto.RecruiterApplicationSummaryResponse;
import com.guardwork.backend.application.dto.ApplicationDetailResponse;
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
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.util.UUID;

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

    @GetMapping("/{applicationId}")
    public ResponseEntity<ApiResponse<ApplicationDetailResponse>> detail(
            @PathVariable Long jobId, @PathVariable UUID applicationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                ApiResponse.ok("Application retrieved successfully",
                        applications.companyDetail(SecurityUtils.getCurrentUserId(), jobId, applicationId)));
    }

    @GetMapping("/{applicationId}/cv")
    public ResponseEntity<byte[]> cv(@PathVariable Long jobId, @PathVariable UUID applicationId) {
        var cv = applications.companyCv(SecurityUtils.getCurrentUserId(), jobId, applicationId);
        String safeName = cv.fileName().replaceAll("[\\r\\n]", "_");
        return ResponseEntity.ok()
                .contentType(cv.contentType())
                .cacheControl(CacheControl.noStore())
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.inline()
                                .filename(safeName, java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(cv.content());
    }
}
