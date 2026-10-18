package com.guardwork.backend.onboarding.dto;

public final class OnboardingRequests {
    private OnboardingRequests() {}
    public record Registration(String fullName, String phone, String companyName, String gender,
                               String workAddress, String email, String password, String passwordConfirmation) {}
    public record Continuation(String continuationToken) {}
    public record EmailProof(String continuationToken, String emailSecret, String password) {}
    public record Phone(String phone) {}
    public record Code(String code) {}
    public record EmailSecret(String emailSecret) {}
    public record CompanyDraft(String companyName, String workAddress, Long expectedVersion) {}
}
