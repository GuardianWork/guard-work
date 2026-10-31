package com.guardwork.backend.account.controller;
import com.guardwork.backend.common.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Order(-2) @RestControllerAdvice(assignableTypes={AccountControlController.class,AccountRecoveryController.class})
public class AccountControlErrorHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> expected(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).headers(e.getHeaders()).body(new ApiResponse<>(e.getStatusCode().value(),e.getReason(),null));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> malformed() {return ResponseEntity.badRequest().body(new ApiResponse<>(400,"40001 INVALID_INPUT",null));}
}
