package com.guardwork.backend.report.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.*;
import com.guardwork.backend.report.dto.ReportResponse.*;
import com.guardwork.backend.report.service.UserReportService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.*;

@RestController
@RequestMapping("/api/staff/reports")
public class StaffReportController {
  private final UserReportService reports;
  public StaffReportController(UserReportService reports) { this.reports = reports; }

  @GetMapping
  public ApiResponse<PageResponse<Summary>> search(@AuthenticationPrincipal UserPrincipal actor,
      @RequestParam(defaultValue = "ALL") String status,
      @RequestParam(defaultValue = "ALL") String targetType,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(reports.search(actor.getId(), status, targetType, page, size));
  }

  @GetMapping("/{id}")
  public ApiResponse<Detail> detail(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
    return ApiResponse.ok(reports.detail(actor.getId(), id));
  }

  @GetMapping("/{id}/evidence/{evidenceId}")
  public ResponseEntity<byte[]> evidence(@AuthenticationPrincipal UserPrincipal actor,
      @PathVariable Long id, @PathVariable UUID evidenceId) {
    var file = reports.download(actor.getId(), id, evidenceId);
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(file.getFilename(), StandardCharsets.UTF_8).build().toString())
        .contentType(MediaType.parseMediaType(file.getContentType())).body(file.getContent());
  }
}
