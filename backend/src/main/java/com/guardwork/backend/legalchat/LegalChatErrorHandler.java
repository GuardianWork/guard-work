package com.guardwork.backend.legalchat;

import com.guardwork.backend.common.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;

/** Keep expected chat failures in the scoped API envelope rather than the global 500 handler. */
@Order(1)
@RestControllerAdvice(assignableTypes = LegalChatController.class)
public class LegalChatErrorHandler {
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiResponse<Void>> malformed() {
    return ResponseEntity.badRequest().body(new ApiResponse<>(400, "40024 INVALID_LEGAL_QUESTION", null));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiResponse<Void>> expected(ResponseStatusException error) {
    return ResponseEntity.status(error.getStatusCode()).body(new ApiResponse<>(
        error.getStatusCode().value(), error.getReason(), null));
  }
}
