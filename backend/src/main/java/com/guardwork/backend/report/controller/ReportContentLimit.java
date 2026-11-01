package com.guardwork.backend.report.controller;

import java.io.*;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Bound the JSON part independently of the larger evidence upload allowance. */
@ControllerAdvice(assignableTypes = ReportController.class)
public class ReportContentLimit extends RequestBodyAdviceAdapter {
  private static final int MAX_JSON_BYTES = 32768;

  @Override public boolean supports(MethodParameter method, Type target,
      Class<? extends HttpMessageConverter<?>> converter) {
    return true;
  }

  @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter method,
      Type target, Class<? extends HttpMessageConverter<?>> converter) throws IOException {
    if (input.getHeaders().getContentLength() > MAX_JSON_BYTES) throw oversized();
    byte[] bytes = input.getBody().readNBytes(MAX_JSON_BYTES + 1);
    if (bytes.length > MAX_JSON_BYTES) throw oversized();
    return new HttpInputMessage() {
      @Override public HttpHeaders getHeaders() { return input.getHeaders(); }
      @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
    };
  }

  private ResponseStatusException oversized() {
    return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "REPORT_CONTENT_TOO_LARGE");
  }
}
