package com.guardwork.backend.auth;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.auth.service.AccountEmailVerificationService;
import com.guardwork.backend.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.Map;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/email-verification")
public class AccountEmailVerificationController {
    private final AccountEmailVerificationService verification;

    public AccountEmailVerificationController(AccountEmailVerificationService verification) {
        this.verification = verification;
    }

    public record Confirmation(String emailSecret) {
        @Override public String toString() { return "Confirmation[emailSecret=REDACTED]"; }
    }

    @PostMapping("/confirm")
    public ApiResponse<Map<String, Boolean>> confirm(@AuthenticationPrincipal UserPrincipal actor,
            @RequestBody(required = false) Confirmation request) {
        verification.confirm(actor.getId(), request == null ? null : request.emailSecret());
        return ApiResponse.ok("Email verified", Map.of("emailVerified", true));
    }

    @PostMapping("/request")
    public ApiResponse<AccountEmailVerificationService.RequestResult> request(@AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Verification delivery attempted", verification.request(actor.getId()));
    }
}
