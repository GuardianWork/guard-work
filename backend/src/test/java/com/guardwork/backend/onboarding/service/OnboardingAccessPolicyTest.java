package com.guardwork.backend.onboarding.service;

import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.onboarding.model.EmployerProfile;
import com.guardwork.backend.onboarding.repository.EmployerProfileRepository;
import com.guardwork.backend.onboarding.repository.VerificationAttemptRepository;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OnboardingAccessPolicyTest {
    private final EmployerProfileRepository profiles = mock(EmployerProfileRepository.class);
    private final VerificationAttemptRepository attempts = mock(VerificationAttemptRepository.class);
    private final CompanyMembershipRepository members = mock(CompanyMembershipRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final OnboardingAccessPolicy policy = new OnboardingAccessPolicy(profiles, attempts, members, companies, users);
    private final Company company = new Company();
    private final User user = new User();

    @BeforeEach void setup() {
        when(profiles.findById(1L)).thenReturn(Optional.empty());
        when(members.findActiveCompanyId(1L)).thenReturn(Optional.of(2L));
        when(attempts.existsByCompanyId(2L)).thenReturn(true);
        when(companies.findById(2L)).thenReturn(Optional.of(company));
        when(users.findById(1L)).thenReturn(Optional.of(user));
    }

    @Test void jobRoutesAllowDraftAndAssignmentOperationsWithoutPersonalProof() {
        assertTrue(policy.permits(1L, "/api/companies/me/jobs", "POST"));
        assertTrue(policy.permits(1L, "/api/companies/me/jobs/3/assignments/4", "PUT"));
        assertTrue(policy.permits(1L, "/api/companies/me/jobs/3/assignments/4", "DELETE"));
        when(profiles.findById(1L)).thenReturn(Optional.of(new EmployerProfile()));
        assertTrue(policy.permits(1L, "/api/companies/me/jobs/3/revisions/5/submit", "POST"));
    }

    @Test void jobRoutesDoNotAllowUnrelatedRoutesOrUnsupportedMethods() {
        assertFalse(policy.permits(1L, "/api/companies/me/invitations", "POST"));
        assertFalse(policy.permits(1L, "/api/companies/me/jobs/3/unknown", "POST"));
        assertFalse(policy.permits(1L, "/api/companies/me/jobs", "PATCH"));
    }

    @Test void jobRoutesStillRejectSuspendedUsersAndCompanies() {
        user.setStatus("SUSPENDED");
        assertFalse(policy.permits(1L, "/api/companies/me/jobs", "POST"));
        user.setStatus("ACTIVE");
        company.setBanned(true);
        assertFalse(policy.permits(1L, "/api/companies/me/jobs", "POST"));
    }

    @Test void jobRoutesDoNotGiveEnrolledNonmemberCompanyJobAccess() {
        when(profiles.findById(1L)).thenReturn(Optional.of(new EmployerProfile()));
        when(members.findActiveCompanyId(1L)).thenReturn(Optional.empty());
        assertFalse(policy.permits(1L, "/api/companies/me/jobs", "POST"));
    }

}
