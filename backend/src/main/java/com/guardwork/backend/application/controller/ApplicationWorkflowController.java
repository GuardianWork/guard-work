package com.guardwork.backend.application.controller;

import com.guardwork.backend.application.dto.ApplicationDetailResponse;
import com.guardwork.backend.application.dto.ApplicationStatusChangeRequest;
import com.guardwork.backend.application.dto.ApplicationWithdrawalRequest;
import com.guardwork.backend.application.service.ApplicationWorkflowService;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class ApplicationWorkflowController {
    private final ApplicationWorkflowService workflow;

    public ApplicationWorkflowController(ApplicationWorkflowService workflow) {
        this.workflow = workflow;
    }

    @PatchMapping("/api/companies/me/jobs/{jobId}/applications/{applicationId}/status")
    public ResponseEntity<ApiResponse<ApplicationDetailResponse>> status(
            @PathVariable Long jobId,
            @PathVariable UUID applicationId,
            @Valid @RequestBody ApplicationStatusChangeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
                "Application status updated successfully",
                workflow.changeStatus(SecurityUtils.getCurrentUserId(), jobId, applicationId, request)));
    }

    @PostMapping("/api/candidate/applications/{applicationId}/withdraw")
    public ResponseEntity<ApiResponse<ApplicationDetailResponse>> withdraw(
            @PathVariable UUID applicationId,
            @Valid @RequestBody ApplicationWithdrawalRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
                "Application withdrawn successfully",
                workflow.withdraw(SecurityUtils.getCurrentUserId(), applicationId, request)));
    }
}
