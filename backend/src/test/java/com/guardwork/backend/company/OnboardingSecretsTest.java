package com.guardwork.backend.company;

import com.guardwork.backend.onboarding.service.OnboardingSecrets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnboardingSecretsTest {
    @Test
    void vietnamesePhoneAliasesHaveOneIdentity() {
        assertEquals("+84912345678", OnboardingSecrets.phone("0912345678"));
        assertEquals("+84912345678", OnboardingSecrets.phone("+84912345678"));
        assertThrows(RuntimeException.class, () -> OnboardingSecrets.phone("+12025550123"));
        assertThrows(RuntimeException.class, () -> OnboardingSecrets.phone("0123456789"));
    }

    @Test
    void otpProofIsBoundToAccountPhoneAndSecret() {
        String proof = OnboardingSecrets.otpHash("server-secret", 1L, "+84912345678", "123456");
        assertNotEquals(proof, OnboardingSecrets.otpHash("server-secret", 2L, "+84912345678", "123456"));
        assertNotEquals(proof, OnboardingSecrets.otpHash("other-secret", 1L, "+84912345678", "123456"));
        assertThrows(RuntimeException.class, () -> OnboardingSecrets.otpHash("", 1L, "+84912345678", "123456"));
    }
}
