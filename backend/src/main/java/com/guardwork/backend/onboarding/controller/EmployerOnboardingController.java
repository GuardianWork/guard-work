package com.guardwork.backend.onboarding.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    public void retiredStatus() { throw retired(); }

    @PutMapping("/api/me/employer-onboarding/company")
    public void retiredCompanyDraft() { throw retired(); }

    static ResponseStatusException retired() {
        return new ResponseStatusException(HttpStatus.GONE,
                "41030 ONBOARDING_RETIRED: Use shared registration and Company setup");
    }
}
