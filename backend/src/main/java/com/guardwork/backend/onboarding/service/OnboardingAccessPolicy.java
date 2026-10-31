package com.guardwork.backend.onboarding.service;

import com.guardwork.backend.auth.service.AccountEmailVerificationService;
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
        if ("GET".equals(method) && "/api/jobs/search".equals(path)) return;
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
        try {
            requireAccess(actor, path, method);
            return true;
        } catch (ResponseStatusException denied) {
            return false;
        }
    }
}
