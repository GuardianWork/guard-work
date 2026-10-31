package com.guardwork.backend.candidate;

import com.guardwork.backend.common.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Preserve HTTP statuses for scoped profile validation and contact conflicts. */
@Hidden
@Order(-1)
@RestControllerAdvice(assignableTypes = CandidateProfileController.class)
public class CandidateProfileErrorHandler {
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> malformed() {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400,"40001 INVALID_INPUT",null));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> expected(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(new ApiResponse<>(error.getStatusCode().value(), error.getReason(), null));
    }
}
