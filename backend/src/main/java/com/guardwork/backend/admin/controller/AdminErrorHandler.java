package com.guardwork.backend.admin.controller;

import com.guardwork.backend.common.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Maps Admin API status exceptions to the standard response envelope. */
@RestControllerAdvice(basePackages = "com.guardwork.backend.admin")
@Order(0)
public class AdminErrorHandler {
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiResponse<Void>> status(ResponseStatusException error) {
    int status = error.getStatusCode().value();
    return ResponseEntity.status(status).body(new ApiResponse<>(status, error.getReason(), null));
  }
}
