package com.guardwork.backend.common.exception;

import com.guardwork.backend.common.api.ErrorApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

@Hidden
@Slf4j
@RestControllerAdvice
@Order(0)
public class GlobalExceptionalHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorApiResponse> handleAppException(AppException ex) {
        log.warn("AppException occurred: {}", ex.getMessage());
        return ResponseEntity.status(ex.getErrorCode().getStatusCode())
                .body(ErrorApiResponse.builder()
                        .message(ex.getErrorCode().getDetailedMessage())
                        .status(ex.getErrorCode().getCode())
                        .detailMessage(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(value = BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorApiResponse handlingBindException(BindException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        ErrorCode errorCode = ErrorCode.INVALID_INPUT;
        log.warn("Validation failed: {} field error(s)", errors.size());

        return ErrorApiResponse.builder()
                .message("Invalid Request Data")
                .status(errorCode.getCode())
                .detailMessage(errorCode.getDetailedMessage())
                .errors(errors)
                .build();
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorApiResponse handleNoResourceFoundException(NoResourceFoundException ex) {
        log.error("No resource found for {}", ex.getResourcePath());

        return ErrorApiResponse.builder()
                .message("Resource not found for " + ex.getResourcePath())
                .build();

    }

}
