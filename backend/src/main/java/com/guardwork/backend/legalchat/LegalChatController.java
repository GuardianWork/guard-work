package com.guardwork.backend.legalchat;

import java.util.Map;
import com.guardwork.backend.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class LegalChatController {
  private final LegalChatService service;

  public LegalChatController(LegalChatService service) { this.service = service; }

  @PostMapping("/api/candidates/me/legal-chat")
  public ApiResponse<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
    return ApiResponse.ok(service.chat(request));
  }
}
