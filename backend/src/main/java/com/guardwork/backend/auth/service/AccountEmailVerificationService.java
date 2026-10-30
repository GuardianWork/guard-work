package com.guardwork.backend.auth.service;

import com.guardwork.backend.auth.Portal;
import com.guardwork.backend.logging.DiagnosticEvent;
import com.guardwork.backend.logging.DiagnosticLog;
import com.guardwork.backend.auth.model.UserEmailVerification;
import com.guardwork.backend.auth.repository.UserEmailVerificationRepository;
import com.guardwork.backend.membership.service.CompanyAccessService;
import com.guardwork.backend.onboarding.delivery.OnboardingDelivery;
import com.guardwork.backend.onboarding.service.OnboardingSecrets;
import com.guardwork.backend.user.model.User;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountEmailVerificationService {
    private static final Logger log = LoggerFactory.getLogger(AccountEmailVerificationService.class);
    private static final Duration LIFETIME = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private final UserEmailVerificationRepository proofs;
    private final CompanyAccessService access;
    private final OnboardingDelivery delivery;
    private final Clock clock;
    private final TransactionTemplate issuance;

    public AccountEmailVerificationService(UserEmailVerificationRepository proofs, CompanyAccessService access,
            OnboardingDelivery delivery, Clock clock, PlatformTransactionManager transactions) {
        this.proofs = proofs;
        this.access = access;
        this.delivery = delivery;
        this.clock = clock;
        this.issuance = new TransactionTemplate(transactions);
        // Mail is attempted only after the new proof has committed, even with an outer caller transaction.
        this.issuance.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record RequestResult(boolean deliveryAccepted, boolean alreadyVerified,
                                Instant nextRequestAt, Instant expiresAt) {}

    public RequestResult request(Long actorId) {
        String secret = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        var issued = issuance.execute(status -> {
            User user = access.requireActiveUser(actorId);
            Portal.PUBLIC.requireRole(user.getRole());
            if (user.isEmailVerified()) return new Issued(null, null, null);
            var previous = proofs.findById(actorId);
            Instant now = clock.instant();
            if (previous.isPresent() && now.isBefore(previous.get().getIssuedAt().plus(COOLDOWN))) {
                long seconds = Math.max(1, (Duration.between(now, previous.get().getIssuedAt().plus(COOLDOWN)).toMillis() + 999) / 1000);
                throw new EmailRequestCooldown(seconds);
            }
            UserEmailVerification proof = previous.orElseGet(UserEmailVerification::new);
            proof.setUserId(actorId);
            proof.setEmail(normalized(user.getEmail()));
            proof.setSecretHash(OnboardingSecrets.hash(secret));
            proof.setIssuedAt(now);
            proof.setExpiresAt(now.plus(LIFETIME));
            proof.setConsumedAt(null);
            proof.setFailedAttempts(0);
            proofs.saveAndFlush(proof);
            return new Issued(user.getEmail(), now.plus(COOLDOWN), proof.getExpiresAt());
        });
        if (issued.email() == null) return new RequestResult(false, true, null, null);
        boolean accepted;
        try {
            accepted = delivery.memberEmail(issued.email(), secret);
        } catch (RuntimeException failure) {
            DiagnosticLog.error(log, DiagnosticEvent.UNEXPECTED_FAILURE, failure);
            accepted = false;
        }
        return new RequestResult(accepted, false, issued.nextRequestAt(), issued.expiresAt());
    }

    public void confirm(Long actorId, String secret) {
        // Return failure from the transaction so failed guesses commit before the HTTP error is raised.
        boolean confirmed = Boolean.TRUE.equals(issuance.execute(status -> {
            User user = access.requireActiveUser(actorId);
            Portal.PUBLIC.requireRole(user.getRole());
            var proof = proofs.findById(actorId).orElse(null);
            Instant now = clock.instant();
            if (proof == null || proof.getConsumedAt() != null
                    || proof.getFailedAttempts() >= MAX_FAILED_ATTEMPTS
                    || !normalized(user.getEmail()).equals(proof.getEmail())
                    || !now.isBefore(proof.getExpiresAt())) return false;
            if (secret == null || !secret.matches("[0-9]{6}")
                    || !OnboardingSecrets.matches(secret, proof.getSecretHash())) {
                proof.setFailedAttempts(proof.getFailedAttempts() + 1);
                if (proof.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) proof.setSecretHash(null);
                proofs.saveAndFlush(proof);
                return false;
            }
            user.setEmailVerified(true);
            proof.setSecretHash(null);
            proof.setConsumedAt(now);
            proofs.saveAndFlush(proof);
            return true;
        }));
        if (!confirmed) throw invalidProof();
    }

    private static class EmailRequestCooldown extends ResponseStatusException {
        private final HttpHeaders headers = new HttpHeaders();
        EmailRequestCooldown(long seconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "42930 EMAIL_REQUEST_COOLDOWN: Wait before requesting another verification email");
            headers.set("Retry-After", Long.toString(seconds));
        }
        @Override public HttpHeaders getHeaders() { return headers; }
    }

    private static ResponseStatusException invalidProof() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "40030 INVALID_EMAIL_PROOF: Invalid or expired email proof");
    }

    private record Issued(String email, Instant nextRequestAt, Instant expiresAt) {}

    public static boolean isRecruitingEmailEligible(User user) {
        return "ACTIVE".equals(user.getStatus())
                && user.isEmailVerified();
    }

    /** Call with the current persisted User already held by the business transaction's actor lock. */
    public static void requireRecruitingEmailEligible(User user) {
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "40304 ACCOUNT_SUSPENDED: Account is suspended");
        }
        if (!isRecruitingEmailEligible(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "40335 EMAIL_VERIFICATION_REQUIRED: Verify your account email before recruiting");
        }
    }

    private static String normalized(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
