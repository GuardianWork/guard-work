package com.guardwork.backend.aihealth;

import com.guardwork.backend.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "AI service", description = "Public readiness check for the local AI adapter")
public class AiHealthController {
  private final AiHealthService service;

  public AiHealthController(AiHealthService service) { this.service = service; }

  @GetMapping("/api/ai/health")
  @Operation(summary = "Check AI service readiness",
      description = "Public endpoint; no access token required. Checks the adapter within two seconds and compares mode, "
          + "profile and corpus snapshot with backend configuration. Does not call a model or verify provider availability.")
  @ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Adapter ready; configuration matches"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "AI disabled, adapter unavailable or configuration mismatched")
  })
  public ResponseEntity<ApiResponse<AiHealthService.View>> health() {
    var result = service.check();
    int status = result.status() == AiHealthService.Status.READY ? 200 : 503;
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
        .body(new ApiResponse<>(status, status == 200 ? "Success" : "50322 AI_SERVICE_NOT_READY", result));
  }
}
