package com.guardwork.backend.report.controller;

import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.report.service.UserReportService;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackages = "com.guardwork.backend.report")
@Order(0)
public class ReportErrorHandler {
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiResponse<Void>> status(ResponseStatusException error) {
    int code = error.getStatusCode().value();
    return ResponseEntity.status(code).body(new ApiResponse<>(code, error.getReason(), null));
  }

  @ExceptionHandler({
      org.springframework.http.converter.HttpMessageNotReadableException.class,
      org.springframework.web.multipart.support.MissingServletRequestPartException.class,
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
      org.springframework.web.HttpMediaTypeNotSupportedException.class})
  public ResponseEntity<ApiResponse<Void>> invalid(Exception failure) {
    return status(UserReportService.invalid());
  }
}
