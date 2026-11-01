package com.guardwork.backend.report.controller;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/** Exact servlet mapping scopes the larger request allowance to report intake only. */
@Configuration
public class ReportUploadConfiguration {
  @Bean
  public ServletRegistrationBean<DispatcherServlet> reportUploadServlet(WebApplicationContext context) {
    var servlet = new DispatcherServlet(context);
    servlet.setPublishContext(false);
    var registration = new ServletRegistrationBean<>(servlet, "/api/reports");
    registration.setName("reportUploadServlet");
    registration.setMultipartConfig(new MultipartConfigElement("", 5L * 1024 * 1024, 16L * 1024 * 1024, 0));
    return registration;
  }
}
