package com.guardwork.backend.onboarding.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.onboarding.dto.OnboardingRequests.*;
import com.guardwork.backend.onboarding.service.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

/** Compatibility tombstones never bind or consume retained onboarding credentials. */
@RestController
public class EmployerOnboardingController {
    @PostMapping({"/api/employer-registrations",
            "/api/employer-registrations/{id}/email/resend",
            "/api/employer-registrations/{id}/email/verify",
            "/api/me/employer-onboarding/email/send",
            "/api/me/employer-onboarding/email/verify",
            "/api/me/employer-onboarding/phone/send",
            "/api/me/employer-onboarding/phone/verify"})
    public void retiredPost() { throw retired(); }

    @GetMapping("/api/me/employer-onboarding")
    public ApiResponse<PersonalVerificationService.State> state(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("Onboarding status", personal.state(user.getId()));
    }

    @PostMapping("/api/me/employer-onboarding/email/send")
    public ApiResponse<Map<String, Boolean>> sendEmail(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("Verification delivery attempted", Map.of("deliveryAccepted", personal.sendMemberEmail(user.getId())));
    }

    @PostMapping("/api/me/employer-onboarding/email/verify")
    public ApiResponse<Void> verifyEmail(@AuthenticationPrincipal UserPrincipal user, @RequestBody EmailSecret request) {
        personal.verifyMemberEmail(user.getId(), request); return ApiResponse.ok("Email verified", null);
    }

    @PostMapping({"/api/me/employer-onboarding/phone/send", "/api/me/employer-onboarding/phone/verify"})
    public ApiResponse<Void> retiredPhone() {
        // Normal authentication runs first; do not enroll, inspect proof or write a profile.
        throw new ResponseStatusException(HttpStatus.GONE,
                "41030 PHONE_VERIFICATION_RETIRED: Phone verification is no longer available");
    }

    @PutMapping("/api/me/employer-onboarding/company")
    public void retiredCompanyDraft() { throw retired(); }

    static ResponseStatusException retired() {
        return new ResponseStatusException(HttpStatus.GONE,
                "41030 ONBOARDING_RETIRED: Use shared registration and Company setup");
    }
}
