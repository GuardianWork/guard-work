package com.guardwork.backend.report.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.report.dto.ReportResponse.Receipt;
import com.guardwork.backend.report.service.UserReportService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
  private final UserReportService reports;
  public ReportController(UserReportService reports) { this.reports = reports; }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<Receipt> submit(@AuthenticationPrincipal UserPrincipal actor,
      @RequestPart("report") Map<String, Object> report,
      @RequestPart(value = "file", required = false) List<MultipartFile> files, HttpServletRequest request) {
    return ApiResponse.ok(reports.submit(actor.getId(), report, files,
        request.getRemoteAddr(), request.getHeader("User-Agent")));
  }
}
