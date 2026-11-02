package com.guardwork.backend.report.controller;

import com.guardwork.backend.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Multipart parsing can fail before a controller exists, so package advice cannot handle it. */
@Configuration(proxyBeanMethods = false)
public class ReportMultipartLimitHandler implements WebMvcConfigurer {
  private final ObjectMapper json;

  public ReportMultipartLimitHandler(ObjectMapper json) { this.json = json; }

  @Override public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
    resolvers.addFirst((request, response, handler, failure) -> {
      if (!(failure instanceof MaxUploadSizeExceededException)) return null;
      String path = request.getRequestURI().substring(request.getContextPath().length());
      String message;
      if ("/api/reports".equals(path)) message = "REPORT_CONTENT_TOO_LARGE";
      else if (path.equals("/api/companies/verification-request") || path.equals("/api/companies/me/verification")
          || path.matches("/api/companies/[0-9]+/resubmit")) {
        message = "41330 DOCUMENT_TOO_LARGE: Maximum document size is 10 MiB";
      } else return null; // Let existing resolvers handle every unrelated route unchanged.
      response.setStatus(413);
      response.setContentType("application/json");
      try { json.writeValue(response.getOutputStream(), new ApiResponse<>(413, message, null)); }
      catch (IOException unavailable) { throw new UncheckedIOException(unavailable); }
      return new ModelAndView();
    });
  }
}
