package com.guardwork.backend.account.controller;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.guardwork.backend.account.service.AccountControlService;
import com.guardwork.backend.account.service.AccountControlService.Action;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.common.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/me/account")
public class AccountControlController {
    private final AccountControlService service;
    public AccountControlController(AccountControlService service) {this.service=service;}
    public record Edit(String firstName,String lastName,String phoneNumber) {
        @JsonAnySetter public void reject(String name,Object value) {throw new IllegalArgumentException("Unsupported account field");}
    }
    public record Proof(String currentPassword,String emailSecret,Boolean acknowledgeConsequences) {}
    @GetMapping public ApiResponse<AccountControlService.AccountView> get() {return ApiResponse.ok(service.view(SecurityUtils.getCurrentUserId()));}
    @PutMapping public ApiResponse<AccountControlService.AccountView> edit(@RequestBody Edit r) {
        requireBody(r);
        return ApiResponse.ok(service.update(SecurityUtils.getCurrentUserId(),r.firstName(),r.lastName(),r.phoneNumber()));
    }
    @PostMapping("/{operation}/request-code") public ApiResponse<AccountControlService.DeliveryResult> request(@PathVariable String operation,@RequestBody Proof r) {
        requireBody(r);
        return ApiResponse.ok(service.requestClosure(SecurityUtils.getCurrentUserId(),action(operation),r.currentPassword()));
    }
    @PostMapping("/{operation}/confirm") public ApiResponse<AccountControlService.ActionResult> confirm(@PathVariable String operation,@RequestBody Proof r) {
        requireBody(r);
        Action selected = action(operation);
        var result = service.confirmClosure(SecurityUtils.getCurrentUserId(), selected, r.currentPassword(), r.emailSecret(), Boolean.TRUE.equals(r.acknowledgeConsequences()));
        return ApiResponse.ok(selected == Action.DELETE
                ? "Account deletion request pending; permanent processing is deferred" : "Account deactivated", result);
    }
    private void requireBody(Object body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40001 INVALID_INPUT");
    }
    private Action action(String operation) {
        return switch(operation) {case "deactivation" -> Action.DEACTIVATE;case "deletion" -> Action.DELETE;
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND,"40400 NOT_FOUND");};
    }
}
