package com.guardwork.backend.job.controller;

import com.guardwork.backend.job.dto.JobRequests;
import com.guardwork.backend.job.service.DraftContent;
import java.io.*;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Bounds both declared-length and streamed content before JSON parsing. */
@ControllerAdvice(assignableTypes = JobDraftController.class)
public class JobContentLimit extends RequestBodyAdviceAdapter {
  @Override public boolean supports(MethodParameter method, Type target,
      Class<? extends HttpMessageConverter<?>> converter) {
    return target == JobRequests.Create.class || target == JobRequests.Save.class;
  }
  @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter method,
      Type target, Class<? extends HttpMessageConverter<?>> converter) throws IOException {
    if (input.getHeaders().getContentLength() > 262144) throw DraftContent.invalid("content request size (maximum 256 KiB)");
    byte[] bytes = input.getBody().readNBytes(262145);
    if (bytes.length > 262144) throw DraftContent.invalid("content request size (maximum 256 KiB)");
    return new HttpInputMessage() {
      @Override public HttpHeaders getHeaders() { return input.getHeaders(); }
      @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
    };
  }
}
