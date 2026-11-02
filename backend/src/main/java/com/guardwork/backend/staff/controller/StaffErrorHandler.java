package com.guardwork.backend.staff.controller;

import com.guardwork.backend.logging.DiagnosticLog;
import com.guardwork.backend.logging.DiagnosticEvent;

import com.guardwork.backend.common.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Maps Staff API status exceptions to the standard response envelope. */
@RestControllerAdvice(basePackages = "com.guardwork.backend.staff")
@Order(0)
public class StaffErrorHandler {
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(StaffErrorHandler.class);
  @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
  public ResponseEntity<ApiResponse<Void>> invalid(Exception error) {
    return ResponseEntity.badRequest().body(new ApiResponse<>(400,"40092 INVALID_ACCOUNT_INPUT",null));
  }

  @ExceptionHandler({org.springframework.dao.ConcurrencyFailureException.class})
  public ResponseEntity<ApiResponse<Void>> conflict(Exception error) {
    return ResponseEntity.status(409).body(new ApiResponse<>(409,"40992 ACCOUNT_STATE_CONFLICT",null));
  }

  @ExceptionHandler({org.springframework.dao.DataAccessException.class, org.springframework.transaction.TransactionException.class})
  public ResponseEntity<ApiResponse<Void>> unavailable(Exception error) {
    DiagnosticLog.error(log,DiagnosticEvent.UNEXPECTED_FAILURE,error);
    return ResponseEntity.status(503).body(new ApiResponse<>(503,"50392 ENFORCEMENT_UNAVAILABLE",null));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiResponse<Void>> status(ResponseStatusException error) {
    int status = error.getStatusCode().value();
    return ResponseEntity.status(status).body(new ApiResponse<>(status, error.getReason(), null));
  }
}
