package com.guardwork.backend.onboarding.service;

import com.guardwork.backend.company.model.VerificationStatus;
import com.guardwork.backend.membership.model.CompanyRole;
import com.guardwork.backend.membership.model.MembershipStatus;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.onboarding.repository.*;
import com.guardwork.backend.user.repository.UserRepository;
import com.guardwork.backend.user.model.User;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Scoped request prerequisite check; business services recheck authority under their actor lock. */
@Service
public class OnboardingAccessPolicy {
    private final UserRepository users;

    public OnboardingAccessPolicy(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public void requireAccess(Long actor, String path, String method) {
        if ("GET".equals(method) && path.matches("/api/jobs/[1-9][0-9]*")) return;
        if ("POST".equals(method) && Set.of("/api/auth/register", "/api/auth/login", "/api/auth/refresh",
                "/api/auth/logout", "/api/auth/forgot-password", "/api/auth/reset-password").contains(path)) return;
        var user = currentActiveUser(actor);
        boolean companyRoute = path.equals("/api/companies") || path.startsWith("/api/companies/");
        boolean statusRead = "GET".equals(method) && Set.of("/api/companies/me", "/api/companies/me/verification",
                "/api/companies/me/verification/history").contains(path);
        if (companyRoute && !statusRead) AccountEmailVerificationService.requireRecruitingEmailEligible(user);
        // Candidate, verification, own inbox and authorized evidence/status reads retain feature checks.
        // Recipient invitation acceptance checks proof after its historical-receipt branch, in service.
    }

    @Transactional(readOnly = true)
    public void requireActiveAccount(Long actor) {
        currentActiveUser(actor);
    }

    private User currentActiveUser(Long actor) {
        var user = users.findById(actor).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "40100 UNAUTHORIZED: Authentication required"));
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "40304 ACCOUNT_SUSPENDED: Account is suspended");
        }
        return user;
    }

    @Transactional(readOnly = true)
    public boolean permits(Long actor, String path, String method) {
        var profile = profiles.findById(actor);
        var companyId = members.findActiveCompanyId(actor);
        boolean enhancedMembership = companyId.isPresent() && attempts.existsByCompanyId(companyId.get());
        if (profile.isEmpty() && !enhancedMembership) return true; // Legacy rollout is explicitly isolated.
        var user = users.findById(actor).filter(u -> "ACTIVE".equals(u.getStatus()));
        if (user.isEmpty()) return false;
        if (shellOrProof(path, method) || retired(path, method)) return true;
        if (profile.isEmpty() || !EmailProof.verified(profile.get(), user.get().getEmail())) return false;

        if (path.startsWith("/api/v1/candidate")) return !profile.get().isApplicant();
        if ("POST".equals(method) && (path.equals("/api/company-invitations/preview")
                || path.equals("/api/company-invitations/accept") || path.equals("/api/company-invitations/decline"))) return true;
        if ("GET".equals(method) && (path.equals("/api/companies/me")
                || path.equals("/api/companies/me/verification") || path.equals("/api/companies/me/verification/history")
                || path.matches("/api/company-verification-documents/[^/]+")
                || path.equals("/api/me/notifications"))) return true;

        if ("PATCH".equals(method) && path.matches("/api/me/notifications/[0-9]+/read")) return true;
        var company = companyId.flatMap(companies::findById);
        boolean rejectedOwner = company.filter(c -> !c.isBanned() && c.getVerificationStatus() == VerificationStatus.REJECTED)
                .isPresent() && members.findByUserIdAndStatus(actor, MembershipStatus.ACTIVE)
                .filter(m -> m.getRole() == CompanyRole.OWNER).isPresent();
        if ("PUT".equals(method) && path.equals("/api/me/employer-onboarding/company")) return companyId.isEmpty();
        if ("POST".equals(method) && path.equals("/api/me/employer-onboarding/documents"))
            return companyId.isEmpty() || rejectedOwner;
        if ("POST".equals(method) && path.equals("/api/companies/verification-request")) return companyId.isEmpty();
        if (("PUT".equals(method) && path.equals("/api/companies/me/verification"))
                || ("PUT".equals(method) && path.matches("/api/companies/[0-9]+/resubmit"))) return rejectedOwner;
        return company.filter(c -> !c.isBanned() && c.getVerificationStatus() == VerificationStatus.VERIFIED).isPresent();
    }

    private static boolean shellOrProof(String path, String method) {
        return ("GET".equals(method) && (path.equals("/api/auth/me") || path.equals("/api/me/employer-onboarding")))
                || ("PUT".equals(method) && path.equals("/api/auth/change-password"))
                || ("POST".equals(method) && (path.equals("/api/auth/login") || path.equals("/api/auth/refresh")
                || path.equals("/api/auth/logout") || path.equals("/api/auth/forgot-password")
                || path.equals("/api/auth/reset-password") || path.equals("/api/me/employer-onboarding/email/send")
                || path.equals("/api/me/employer-onboarding/email/verify")));
    }

    private static boolean retired(String path, String method) {
        return "POST".equals(method) && (path.equals("/api/me/employer-onboarding/phone/send")
                || path.equals("/api/me/employer-onboarding/phone/verify")
                || path.matches("/api/employer-registrations/[^/]+/email/(resend|verify)"));
    }
}
