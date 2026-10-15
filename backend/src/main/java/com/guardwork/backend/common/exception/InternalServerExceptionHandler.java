package com.guardwork.backend.common.exception;


import com.guardwork.backend.common.api.ErrorApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Hidden
@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
@Slf4j
public class InternalServerExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ErrorApiResponse handleRuntimeException(RuntimeException ex) {
        log.error("Unhandled runtime exception", ex);
        return ErrorApiResponse.builder()
                .message("Internal Server Error")
                .build();
    }

    @ExceptionHandler(Exception.class)
    public ErrorApiResponse handleException(Exception ex) {
        log.error("Unhandled exception", ex);
        return ErrorApiResponse.builder()
                .message("Internal Server Error")
                .build();
    }

}
