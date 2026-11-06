package com.guardwork.backend.application.controller;

import com.guardwork.backend.common.exception.ErrorCode;
import com.guardwork.backend.common.api.ErrorApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Preserve the Candidate ErrorApiResponse envelope for malformed listing parameters. */
@RestControllerAdvice(assignableTypes = JobApplicationController.class)
@Order(0)
public class CandidateApplicationErrorHandler {

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorApiResponse> parameter(MethodArgumentTypeMismatchException error) {
        return ResponseEntity.badRequest().body(ErrorApiResponse.builder()
                .message("Invalid request parameter")
                .status(ErrorCode.INVALID_INPUT.getCode())
                .detailMessage("Invalid request parameter")
                .build());
    }
}
