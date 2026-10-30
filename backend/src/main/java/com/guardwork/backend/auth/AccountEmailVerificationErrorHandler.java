package com.guardwork.backend.auth;

import com.guardwork.backend.common.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Order(-2)
@RestControllerAdvice(assignableTypes = AccountEmailVerificationController.class)
public class AccountEmailVerificationErrorHandler {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> malformed() {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400,
                "40030 INVALID_EMAIL_PROOF: Invalid or expired email proof", null));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> expected(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                .body(new ApiResponse<>(error.getStatusCode().value(), error.getReason(), null));
    }
}
