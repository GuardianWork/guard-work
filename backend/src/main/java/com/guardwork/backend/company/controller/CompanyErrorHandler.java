package com.guardwork.backend.company.controller;

import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import com.guardwork.backend.common.ApiResponse;

/** Scoped AUTH response convention for the protected Company API. */
@RestControllerAdvice(basePackages = {"com.guardwork.backend.company", "com.guardwork.backend.membership", "com.guardwork.backend.invitation", "com.guardwork.backend.notification", "com.guardwork.backend.job"})
@Order(0)
public class CompanyErrorHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> status(ResponseStatusException error) {
        int status = error.getStatusCode().value();
        return ResponseEntity.status(status).body(new ApiResponse<>(status, error.getReason(), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> malformed(HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, "40000 BAD_REQUEST: Invalid request body", null));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> parameter(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException error) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, "40000 BAD_REQUEST: Invalid request parameter", null));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> constraint(DataIntegrityViolationException error) {
        String detail = String.valueOf(error.getMostSpecificCause().getMessage());
        if (detail.contains("uq_company_membership_one_active_per_user")) {
            return ResponseEntity.status(409).body(new ApiResponse<>(409,
                    "40910 ACTIVE_COMPANY_MEMBERSHIP_EXISTS: Active Company membership already exists", null));
        }
        if (detail.contains("uq_company_invitation_one_pending_per_email")) {
            return ResponseEntity.status(409).body(new ApiResponse<>(409,
                    "40911 PENDING_INVITATION_EXISTS: Pending invitation already exists", null));
        }
        if (detail.contains("companies_tax_code_key")) {
            return ResponseEntity.status(409).body(new ApiResponse<>(409,
                    "40901 DUPLICATE_TAX_CODE: Company registration details already registered", null));
        }
        return ResponseEntity.internalServerError().body(new ApiResponse<>(500, "50000 INTERNAL_ERROR: Company operation failed", null));
    }
}
