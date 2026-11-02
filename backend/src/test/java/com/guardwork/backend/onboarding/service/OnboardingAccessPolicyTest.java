package com.guardwork.backend.onboarding.service;

import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OnboardingAccessPolicyTest {
    private final UserRepository users = mock(UserRepository.class);
    private final OnboardingAccessPolicy policy = new OnboardingAccessPolicy(users);
    private final User user = new User();

    @BeforeEach void setup() {
        when(users.findById(1L)).thenReturn(Optional.of(user));
    }

    @Test void newUnverifiedUsersCannotReadOrMutatePrivateCompanyRoutes() {
        assertFalse(policy.permits(1L, "/api/companies/me/jobs", "POST"));
        assertFalse(policy.permits(1L, "/api/companies/me/jobs/3", "GET"));
        assertFalse(policy.permits(1L, "/api/companies/me/jobs/3/assignments/4", "PUT"));
        assertFalse(policy.permits(1L, "/api/companies/me/members", "GET"));
        assertFalse(policy.permits(1L, "/api/companies/me/invitations", "POST"));
        assertFalse(policy.permits(1L, "/api/companies/7", "GET"));
    }

    @Test void verificationRemovesTheEmailPrerequisiteAndLosingItRestoresTheGate() {
        user.setEmailVerified(true);
        assertTrue(policy.permits(1L, "/api/companies/me/jobs", "POST"));
        user.setEmailVerified(false);
        assertFalse(policy.permits(1L, "/api/companies/me/jobs/3", "GET"));
        assertFalse(policy.permits(1L, "/api/companies/me/invitations", "POST"));
    }

    @Test void scopedExceptionsDoNotAllowOtherCompanyContentOrCorrection() {
        assertTrue(policy.permits(1L, "/api/companies/me", "GET"));
        assertTrue(policy.permits(1L, "/api/companies/me/verification", "GET"));
        assertTrue(policy.permits(1L, "/api/companies/me/verification/history", "GET"));
        assertTrue(policy.permits(1L, "/api/company-verification-documents/1", "GET"));
        assertTrue(policy.permits(1L, "/api/candidates/profile", "GET"));
        assertTrue(policy.permits(1L, "/api/me/notifications", "GET"));
        assertTrue(policy.permits(1L, "/api/me/email-verification/request", "POST"));
        assertTrue(policy.permits(1L, "/api/company-invitations/accept", "POST"));
        assertFalse(policy.permits(1L, "/api/companies/me/verification", "PUT"));
        assertFalse(policy.permits(1L, "/api/companies/me/verification/secret", "GET"));
    }

    @Test void suspensionStillBlocksProtectedOperationsButPublicAndRecoveryRemainReachable() {
        user.setStatus("SUSPENDED");
        assertFalse(policy.permits(1L, "/api/candidates/profile", "GET"));
        assertFalse(policy.permits(1L, "/api/me/notifications", "GET"));
        assertFalse(policy.permits(1L, "/api/auth/me", "GET"));
        assertTrue(policy.permits(1L, "/api/jobs/3", "GET"));
        assertTrue(policy.permits(1L, "/api/auth/forgot-password", "POST"));
    }
}
