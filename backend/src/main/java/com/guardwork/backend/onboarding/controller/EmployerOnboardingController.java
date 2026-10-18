package com.guardwork.backend.onboarding.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.onboarding.dto.OnboardingRequests.*;
import com.guardwork.backend.onboarding.service.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class EmployerOnboardingController {
    private final EmployerRegistrationService registrations;
    private final PersonalVerificationService personal;

    public EmployerOnboardingController(EmployerRegistrationService registrations, PersonalVerificationService personal) {
        this.registrations = registrations; this.personal = personal;
    }

    @PostMapping("/api/employer-registrations")
    public ApiResponse<EmployerRegistrationService.Started> register(@RequestBody Registration request) {
        return ApiResponse.ok("Registration saved; verify your email", registrations.begin(request));
    }

    @PostMapping("/api/employer-registrations/{id}/email/resend")
    public ApiResponse<Map<String, Boolean>> resend(@PathVariable UUID id, @RequestBody Continuation request) {
        return ApiResponse.ok("Verification delivery attempted", Map.of("deliveryAccepted", registrations.resend(id, request)));
    }

    @PostMapping("/api/employer-registrations/{id}/email/verify")
    public ApiResponse<Map<String, Long>> verify(@PathVariable UUID id, @RequestBody EmailProof request) {
        return ApiResponse.ok("Email verified; sign in to continue", Map.of("userId", registrations.verify(id, request)));
    }

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

    @PostMapping("/api/me/employer-onboarding/phone/send")
    public ApiResponse<Void> sendPhone(@AuthenticationPrincipal UserPrincipal user, @RequestBody Phone request) {
        personal.sendPhone(user.getId(), request); return ApiResponse.ok("OTP sent", null);
    }

    @PostMapping("/api/me/employer-onboarding/phone/verify")
    public ApiResponse<Void> verifyPhone(@AuthenticationPrincipal UserPrincipal user, @RequestBody Code request) {
        personal.verifyPhone(user.getId(), request); return ApiResponse.ok("Phone verified", null);
    }

    @PutMapping("/api/me/employer-onboarding/company")
    public ApiResponse<Void> company(@AuthenticationPrincipal UserPrincipal user, @RequestBody CompanyDraft request) {
        personal.updateCompany(user.getId(), request); return ApiResponse.ok("Draft updated", null);
    }
}
