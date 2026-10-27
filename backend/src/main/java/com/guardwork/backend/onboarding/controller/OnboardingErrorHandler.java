package com.guardwork.backend.onboarding.controller;

import com.guardwork.backend.common.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Order(-1)
@RestControllerAdvice(basePackages = {"com.guardwork.backend.onboarding.controller", "com.guardwork.backend.auth"})
public class OnboardingErrorHandler {
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Void>> malformed(Exception error) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, "40030 INVALID_ONBOARDING: Invalid request", null));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> oversized(Exception error) {
        return ResponseEntity.status(413).body(new ApiResponse<>(413, "41330 DOCUMENT_TOO_LARGE: Maximum document size is 10 MiB", null));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> expected(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(new ApiResponse<>(error.getStatusCode().value(), error.getReason(), null));
    }

    @ExceptionHandler({DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ApiResponse<Void>> conflict(RuntimeException error) {
        if (error instanceof DataIntegrityViolationException constraint
                && String.valueOf(constraint.getMostSpecificCause().getMessage()).contains("uq_users_phone_number")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiResponse<>(409,
                    "40930 IDENTIFIER_UNAVAILABLE: Registration information is unavailable", null));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiResponse<>(409,
                "40930 ONBOARDING_CONFLICT: Information is unavailable or was changed; reload and retry", null));
    }
}
