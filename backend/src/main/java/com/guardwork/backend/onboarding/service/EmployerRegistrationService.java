package com.guardwork.backend.onboarding.service;

import com.guardwork.backend.onboarding.delivery.OnboardingDelivery;
import com.guardwork.backend.onboarding.dto.OnboardingRequests.*;
import com.guardwork.backend.onboarding.model.*;
import com.guardwork.backend.onboarding.repository.*;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmployerRegistrationService {
    private final EmployerRegistrationRepository registrations;
    private final EmployerProfileRepository profiles;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final OnboardingDelivery delivery;
    private final TransactionTemplate tx;

    public EmployerRegistrationService(EmployerRegistrationRepository registrations, EmployerProfileRepository profiles,
            UserRepository users, PasswordEncoder passwords, OnboardingDelivery delivery, PlatformTransactionManager manager) {
        this.registrations = registrations; this.profiles = profiles; this.users = users;
        this.passwords = passwords; this.delivery = delivery; this.tx = new TransactionTemplate(manager);
    }

    public record Started(UUID registrationId, String continuationToken, boolean deliveryAccepted) {}

    public Started begin(Registration request) {
        if (request == null) throw OnboardingSecrets.invalid("Registration is required");
        String name = OnboardingSecrets.required(request.fullName(), 200);
        String email = OnboardingSecrets.required(request.email(), 255).toLowerCase(Locale.ROOT);
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw OnboardingSecrets.invalid("Invalid email");
        if (request.password() == null || !request.password().matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,128}$")
                || request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
                || !request.password().equals(request.passwordConfirmation())) throw OnboardingSecrets.invalid("Invalid password or confirmation");
        if (!Set.of("MALE", "FEMALE", "OTHER", "UNDISCLOSED").contains(String.valueOf(request.gender())))
            throw OnboardingSecrets.invalid("Gender selection is required");
        String phone = OnboardingSecrets.phone(request.phone());
        String company = OnboardingSecrets.required(request.companyName(), 255);
        String address = OnboardingSecrets.required(request.workAddress(), 1000);
        String continuation = OnboardingSecrets.token();
        String secret = OnboardingSecrets.token();
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            if (users.findByEmail(email).isPresent() || profiles.existsByPhoneAndPhoneVerifiedAtIsNotNull(phone))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "40930 IDENTIFIER_UNAVAILABLE: Registration information is unavailable");
            if (registrations.countByEmailAndCreatedAtAfter(email, Instant.now().minus(Duration.ofHours(1))) >= 5)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "42930 VERIFICATION_RATE_LIMIT: Try again later");
            EmployerRegistration r = new EmployerRegistration();
            r.setId(id); r.setContinuationHash(OnboardingSecrets.hash(continuation)); r.setEmail(email);
            r.setPasswordHash(passwords.encode(request.password())); r.setFullName(name); r.setGender(request.gender());
            r.setPhone(phone); r.setCompanyName(company); r.setWorkAddress(address);
            r.setEmailSecretHash(OnboardingSecrets.hash(secret)); r.setEmailSentAt(Instant.now());
            r.setEmailExpiresAt(Instant.now().plus(Duration.ofHours(24))); r.setCreatedAt(Instant.now());
            registrations.saveAndFlush(r);
        });
        // Return continuation even on delivery failure so this exact private attempt can be retried.
        return new Started(id, continuation, delivery.registrationEmail(email, id, secret));
    }

    public boolean resend(UUID id, Continuation request) {
        String secret = OnboardingSecrets.token();
        String email = tx.execute(status -> {
            var r = privateAttempt(id, request == null ? null : request.continuationToken());
            if (r.getPromotedUserId() != null) throw OnboardingSecrets.invalid("Registration was already completed");
            cooldown(r.getEmailSentAt());
            r.setEmailSecretHash(OnboardingSecrets.hash(secret)); r.setEmailSentAt(Instant.now());
            r.setEmailExpiresAt(Instant.now().plus(Duration.ofHours(24)));
            registrations.saveAndFlush(r);
            return r.getEmail();
        });
        return delivery.registrationEmail(email, id, secret);
    }

    public Long verify(UUID id, EmailProof proof) {
        return tx.execute(status -> {
            var r = privateAttempt(id, proof == null ? null : proof.continuationToken());
            if (!OnboardingSecrets.matches(proof.emailSecret(), r.getEmailSecretHash())
                    || proof.password() == null || !passwords.matches(proof.password(), r.getPasswordHash())) throw invalidProof();
            if (r.getPromotedUserId() != null) return r.getPromotedUserId();
            if (!Instant.now().isBefore(r.getEmailExpiresAt())) throw invalidProof();
            if (users.findByEmail(r.getEmail()).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "40930 IDENTIFIER_UNAVAILABLE: Registration information is unavailable");
            User user = new User();
            user.setUsername("employer_" + UUID.randomUUID().toString().replace("-", ""));
            int boundary = r.getFullName().offsetByCodePoints(0, Math.min(100, r.getFullName().codePointCount(0, r.getFullName().length())));
            user.setFirstName(r.getFullName().substring(0, boundary));
            user.setLastName(r.getFullName().substring(boundary));
            user.setEmail(r.getEmail()); user.setPassword(r.getPasswordHash()); user.setRole("USER");
            user.setStatus("ACTIVE"); user.setEmailVerified(true);
            users.saveAndFlush(user);
            EmployerProfile p = new EmployerProfile();
            p.setUserId(user.getId()); p.setApplicant(true); p.setFullName(r.getFullName()); p.setGender(r.getGender());
            p.setPhone(r.getPhone()); p.setCompanyName(r.getCompanyName()); p.setWorkAddress(r.getWorkAddress());
            p.setEmailVerifiedAt(Instant.now()); profiles.saveAndFlush(p);
            r.setPromotedUserId(user.getId()); registrations.saveAndFlush(r);
            return user.getId();
        });
    }

    private EmployerRegistration privateAttempt(UUID id, String continuation) {
        var r = registrations.findLocked(id).orElseThrow(EmployerRegistrationService::invalidProof);
        if (!OnboardingSecrets.matches(continuation, r.getContinuationHash())) throw invalidProof();
        return r;
    }

    public static void cooldown(Instant sent) {
        if (sent != null && Instant.now().isBefore(sent.plusSeconds(60)))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "42930 VERIFICATION_RATE_LIMIT: Try again later");
    }

    private static ResponseStatusException invalidProof() { return OnboardingSecrets.invalid("Verification proof is invalid or expired"); }
}
