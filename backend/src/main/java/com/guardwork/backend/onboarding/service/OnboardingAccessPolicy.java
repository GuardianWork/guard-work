package com.guardwork.backend.onboarding.service;

import com.guardwork.backend.company.model.VerificationStatus;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.onboarding.repository.*;
import com.guardwork.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OnboardingAccessPolicy {
    private final EmployerProfileRepository profiles;
    private final VerificationAttemptRepository attempts;
    private final CompanyMembershipRepository members;
    private final CompanyRepository companies;
    private final UserRepository users;

    public OnboardingAccessPolicy(EmployerProfileRepository profiles, VerificationAttemptRepository attempts,
            CompanyMembershipRepository members, CompanyRepository companies, UserRepository users) {
        this.profiles = profiles; this.attempts = attempts; this.members = members; this.companies = companies; this.users = users;
    }

    @Transactional(readOnly = true)
    public boolean permits(Long actor, String path, String method) {
        if ("GET".equals(method) && path.matches("/api/jobs/[1-9][0-9]*")) return true;
        if (("GET".equals(method) && path.equals("/api/me/notifications"))
                || ("PATCH".equals(method) && path.matches("/api/me/notifications/[1-9][0-9]*/read")))
            return users.findById(actor).filter(u -> "ACTIVE".equals(u.getStatus())).isPresent();
        var profile = profiles.findById(actor);
        var companyId = members.findActiveCompanyId(actor);
        boolean enhancedMembership = companyId.isPresent() && attempts.existsByCompanyId(companyId.get());
        if (profile.isEmpty() && !enhancedMembership) return true; // Legacy rollout is explicitly isolated.
        if (users.findById(actor).filter(u -> "ACTIVE".equals(u.getStatus())).isEmpty()) return false;
        if (path.startsWith("/api/auth/") || path.startsWith("/api/me/employer-onboarding")
                || path.equals("/api/companies/me") || path.equals("/api/companies/me/verification")
                || path.equals("/api/companies/me/verification/history") || path.startsWith("/api/company-verification-documents/")
                || path.equals("/api/companies/verification-request") || path.matches("/api/companies/[0-9]+/resubmit")
                || path.startsWith("/api/notifications")) return true;
        boolean candidateRoute = path.startsWith("/api/v1/candidate");
        if (candidateRoute) return profile.isEmpty() || !profile.get().isApplicant();
        // Invitation consumption does not grant employer-operation eligibility by itself.
        if (path.startsWith("/api/company-invitations/")) return true;
        if (profile.isEmpty() || profile.get().getEmailVerifiedAt() == null || profile.get().getPhoneVerifiedAt() == null)
            return false;
        if (path.equals("/api/companies/me/jobs") || path.matches("/api/companies/me/jobs/[1-9][0-9]*(/draft)?"))
            return companyId.flatMap(companies::findById).filter(c -> !c.isBanned()).isPresent();
        return companyId.flatMap(companies::findById).filter(c -> !c.isBanned()
                && c.getVerificationStatus() == VerificationStatus.VERIFIED).isPresent();
    }
}
