package com.guardwork.backend.job.controller;

import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.logging.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Expected Company API errors are handled at order 0; retain the job envelope for other failures. */
@Order(1)
@RestControllerAdvice(basePackages = "com.guardwork.backend.job.controller")
public class JobErrorHandler {
  private static final Logger LOG = LoggerFactory.getLogger(JobErrorHandler.class);

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  public ResponseEntity<ApiResponse<Void>> stale(ObjectOptimisticLockingFailureException error) {
    return ResponseEntity.status(409).body(new ApiResponse<>(409,
        "40022 CONCURRENT_MODIFICATION: Reload current versions", null));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiResponse<Void>> expected(ResponseStatusException error) {
    return ResponseEntity.status(error.getStatusCode()).body(new ApiResponse<>(error.getStatusCode().value(), error.getReason(), null));
  }

  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<ApiResponse<Void>> unexpected(RuntimeException error) {
    DiagnosticLog.error(LOG, DiagnosticEvent.UNEXPECTED_FAILURE, error);
    return ResponseEntity.internalServerError().body(new ApiResponse<>(500,
        "50000 INTERNAL_ERROR: Job operation failed", null));
  }
}
