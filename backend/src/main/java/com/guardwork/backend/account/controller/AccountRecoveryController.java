package com.guardwork.backend.account.controller;

import com.guardwork.backend.account.service.AccountControlService;
import com.guardwork.backend.account.service.AccountControlService.Action;
import com.guardwork.backend.auth.Portal;
import com.guardwork.backend.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/auth/account-recovery")
public class AccountRecoveryController {
    private final AccountControlService service;
    public AccountRecoveryController(AccountControlService service) {this.service=service;}
    public record Recovery(String usernameOrEmail,String password,Action action,String emailSecret,Boolean acknowledgeConsequences) {}
    @PostMapping("/request-code") public ApiResponse<AccountControlService.DeliveryResult> request(@RequestBody Recovery r,HttpServletRequest http) {
        validate(r,http);return ApiResponse.ok(service.requestRecovery(r.usernameOrEmail(),r.password(),r.action()));
    }
    @PostMapping("/confirm") public ApiResponse<AccountControlService.ActionResult> confirm(@RequestBody Recovery r,HttpServletRequest http) {
        validate(r,http);return ApiResponse.ok(service.confirmRecovery(r.usernameOrEmail(),r.password(),r.action(),r.emailSecret(),Boolean.TRUE.equals(r.acknowledgeConsequences())));
    }
    private void validate(Recovery r,HttpServletRequest http) {
        if (r == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "40001 INVALID_INPUT");
        Portal portal=Portal.fromRequest(http);
        if(portal!=null && portal!=Portal.PUBLIC) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"40300 FORBIDDEN");
        if(r.action()!=Action.REACTIVATE && r.action()!=Action.CANCEL_DELETION)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"40001 INVALID_INPUT");
    }
}
