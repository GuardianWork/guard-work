package com.guardwork.backend.application.controller;

import com.guardwork.backend.common.ApiResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/** Preserve the Company API's error envelope for Company Application routes. */
@RestControllerAdvice(assignableTypes = RecruiterApplicationController.class)
@Order(0)
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class RecruiterApplicationErrorHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> status(ResponseStatusException error) {
        int status = error.getStatusCode().value();
        return ResponseEntity.status(status).body(new ApiResponse<>(status, error.getReason(), null));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> parameter(MethodArgumentTypeMismatchException error) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400,
                "40000 BAD_REQUEST: Invalid request parameter", null));
    }
}
