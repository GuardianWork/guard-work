package com.guardwork.backend.staff.controller;

import com.guardwork.backend.audit.dto.ActivityItem;
import com.guardwork.backend.audit.service.ActivityService;
import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/staff/me/activities")
public class ActivityController {
  private final ActivityService service;
  public ActivityController(ActivityService service) { this.service=service; }
  @GetMapping
  public ApiResponse<PageResponse<ActivityItem>> search(@AuthenticationPrincipal UserPrincipal actor,
      @RequestParam MultiValueMap<String,String> filters) {
    return ApiResponse.ok("Activity history retrieved successfully",service.search(actor.getId(),filters));
  }
}
