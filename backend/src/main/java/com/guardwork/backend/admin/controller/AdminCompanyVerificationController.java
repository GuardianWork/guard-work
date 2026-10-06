package com.guardwork.backend.admin.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.guardwork.backend.admin.dto.CompanyVerificationResponse;
import com.guardwork.backend.admin.dto.VerificationDecisionRequest;
import com.guardwork.backend.admin.service.AdminCompanyVerificationService;
import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.common.PageResponse;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/admin/companies")
public class AdminCompanyVerificationController {

    private final AdminCompanyVerificationService verificationService;

    public AdminCompanyVerificationController(AdminCompanyVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @GetMapping("/verifications")
    public ApiResponse<PageResponse<CompanyVerificationResponse>> getVerificationQueue(
            @RequestParam(required = false, defaultValue = "PENDING") String status,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size,
            @RequestHeader(value = "X-Admin-Role", required = false) String adminRole
    ) {
        UserPrincipal principal = getAuthenticatedPrincipal();
        String effectiveRole = (adminRole != null && !adminRole.isBlank()) ? adminRole
                : (principal != null ? principal.getRole() : null);
        validateAdminRole(effectiveRole);

        PageResponse<CompanyVerificationResponse> queue = verificationService.getVerificationQueue(status, page, size);
        return ApiResponse.ok("Verification queue retrieved successfully", queue);
    }

    @PutMapping("/{id}/verify")
    public ApiResponse<CompanyVerificationResponse> verifyCompany(
            @PathVariable("id") Long id,
            @RequestBody VerificationDecisionRequest request,
            @RequestHeader(value = "X-Admin-Id", required = false) Long adminId,
            @RequestHeader(value = "X-Admin-Role", required = false) String adminRole,
            HttpServletRequest httpRequest
    ) {
        UserPrincipal principal = getAuthenticatedPrincipal();
        Long effectiveAdminId = adminId != null ? adminId
                : (principal != null ? principal.getId() : null);
        String effectiveRole = (adminRole != null && !adminRole.isBlank()) ? adminRole
                : (principal != null ? principal.getRole() : null);

        if (effectiveAdminId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "40100 UNAUTHORIZED: Missing admin authentication or X-Admin-Id header");
        }
        validateAdminRole(effectiveRole);

        String ipAddress = extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        CompanyVerificationResponse result = verificationService.verifyCompany(id, request, effectiveAdminId, ipAddress, userAgent);
        return ApiResponse.ok("Company verification status updated successfully", result);
    }

    private UserPrincipal getAuthenticatedPrincipal() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) {
            return p;
        }
        return null;
    }

    private void validateAdminRole(String adminRole) {
        if (adminRole == null || adminRole.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "40100 UNAUTHORIZED: Missing admin authorization header");
        }
        if (!"ADMIN".equalsIgnoreCase(adminRole.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "40300 FORBIDDEN: Caller is not an Administrator");
        }
    }

    private String extractClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader != null && !xfHeader.isBlank()) {
            return xfHeader.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "127.0.0.1";
    }
}
