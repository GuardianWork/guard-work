package com.guardwork.backend.staff.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.guardwork.backend.staff.dto.CompanyVerificationResponse;
import com.guardwork.backend.staff.dto.VerificationDecisionRequest;
import com.guardwork.backend.staff.service.StaffCompanyVerificationService;
import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.common.PageResponse;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/staff/companies")
public class StaffCompanyVerificationController {

    private final StaffCompanyVerificationService verificationService;

    public StaffCompanyVerificationController(StaffCompanyVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @GetMapping("/verifications")
    public ApiResponse<PageResponse<CompanyVerificationResponse>> getVerificationQueue(
            @RequestParam(required = false, defaultValue = "PENDING") String status,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size
    ) {
        requireStaff();
        PageResponse<CompanyVerificationResponse> queue = verificationService.getVerificationQueue(status, page, size);
        return ApiResponse.ok("Verification queue retrieved successfully", queue);
    }

    @PutMapping("/{id}/verify")
    public ApiResponse<CompanyVerificationResponse> verifyCompany(
            @PathVariable("id") Long id,
            @RequestBody VerificationDecisionRequest request,
            HttpServletRequest httpRequest
    ) {
        UserPrincipal principal = requireStaff();
        CompanyVerificationResponse result = verificationService.verifyCompany(id, request, principal.getId(),
                extractClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ApiResponse.ok("Company verification status updated successfully", result);
    }

    @PutMapping("/{id}/reject")
    public ApiResponse<CompanyVerificationResponse> rejectCompany(
            @PathVariable("id") Long id,
            @RequestBody VerificationDecisionRequest request,
            HttpServletRequest httpRequest
    ) {
        UserPrincipal principal = requireStaff();
        CompanyVerificationResponse result = verificationService.rejectCompany(id, request, principal.getId(),
                extractClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ApiResponse.ok("Company verification rejected successfully", result);
    }

    private UserPrincipal requireStaff() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "40100 UNAUTHORIZED: Staff authentication required");
        }
        if (!"STAFF".equals(principal.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "40300 FORBIDDEN: Caller is not Platform Staff");
        }
        return principal;
    }

    private String extractClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader != null && !xfHeader.isBlank()) {
            return xfHeader.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "127.0.0.1";
    }
}
