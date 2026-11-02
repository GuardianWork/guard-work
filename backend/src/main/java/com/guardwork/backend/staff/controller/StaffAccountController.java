package com.guardwork.backend.staff.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.*;
import com.guardwork.backend.staff.service.StaffAccountService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/staff/accounts/{type}")
public class StaffAccountController {
  private final StaffAccountService service;
  public StaffAccountController(StaffAccountService service) { this.service=service; }

  @GetMapping
  public ApiResponse<PageResponse<Map<String,Object>>> search(@AuthenticationPrincipal UserPrincipal actor,
      @PathVariable String type,@RequestParam(required=false) String q,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
    return ApiResponse.ok(service.search(actor.getId(),company(type),q,page,size));
  }
  @GetMapping("/{id}")
  public ApiResponse<Map<String,Object>> detail(@AuthenticationPrincipal UserPrincipal actor,
      @PathVariable String type,@PathVariable Long id) {
    return ApiResponse.ok(service.detail(actor.getId(),company(type),id));
  }
  @PostMapping("/{id}/{action}")
  public ApiResponse<Map<String,Object>> change(@AuthenticationPrincipal UserPrincipal actor,
      @PathVariable String type,@PathVariable Long id,@PathVariable String action,
      @RequestBody Map<String,Object> body,HttpServletRequest request) {
    if(!Set.of("ban","unban").contains(action)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"40492 ACCOUNT_ACTION_NOT_FOUND");
    boolean unban="unban".equals(action);
    if(body==null) throw invalid();
    var fields=body.keySet().iterator();
    while(fields.hasNext()) if(!(unban ? Set.of("reason") : Set.of("reason","expiresAt")).contains(fields.next())) throw invalid();
    if(!(body.get("reason") instanceof String)) throw invalid();
    Instant expiresAt=null;
    if(body.get("expiresAt")!=null) {
      if(!(body.get("expiresAt") instanceof String)) throw invalid();
      try { expiresAt=Instant.parse((String)body.get("expiresAt")); }
      catch(java.time.format.DateTimeParseException failure) { throw invalid(); }
    }
    return ApiResponse.ok(service.change(actor.getId(),company(type),id,unban,(String)body.get("reason"),expiresAt,
        request.getRemoteAddr(),request.getHeader("User-Agent")));
  }
  private boolean company(String type) {
    if(!Set.of("users","companies").contains(type)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"40492 ACCOUNT_TYPE_NOT_FOUND");
    return "companies".equals(type);
  }
  private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"40092 INVALID_ACCOUNT_INPUT"); }
}
