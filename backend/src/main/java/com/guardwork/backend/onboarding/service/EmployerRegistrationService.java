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
    private final EmployerProfileRepository profiles;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final OnboardingDelivery delivery;
    private final TransactionTemplate tx;

    public EmployerRegistrationService(EmployerProfileRepository profiles, UserRepository users,
            PasswordEncoder passwords, OnboardingDelivery delivery, PlatformTransactionManager manager) {
        this.profiles = profiles; this.users = users; this.passwords = passwords;
        this.delivery = delivery; this.tx = new TransactionTemplate(manager);
    }

    public record Started(Long userId, boolean emailVerified, String stage, boolean deliveryAccepted) {}

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
        String secret = OnboardingSecrets.token();
        Long id = tx.execute(status -> {
            if (users.existsByNormalizedEmail(email) || users.existsByPhoneNumber(phone))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "40930 IDENTIFIER_UNAVAILABLE: Registration information is unavailable");
            User user = new User();
            user.setUsername("employer_" + UUID.randomUUID().toString().replace("-", ""));
            int boundary = name.offsetByCodePoints(0, Math.min(100, name.codePointCount(0, name.length())));
            user.setFirstName(name.substring(0, boundary)); user.setLastName(name.substring(boundary));
            user.setEmail(email); user.setPassword(passwords.encode(request.password()));
            user.setRole("USER"); user.setStatus("ACTIVE"); user.setEmailVerified(false); user.setPhoneNumber(phone);
            users.saveAndFlush(user);
            EmployerProfile profile = new EmployerProfile();
            profile.setUserId(user.getId()); profile.setApplicant(true); profile.setFullName(name);
            profile.setGender(request.gender()); profile.setCompanyName(company); profile.setWorkAddress(address);
            profile.setEmailChallengeEmail(email); profile.setEmailSecretHash(OnboardingSecrets.hash(secret));
            profile.setEmailSentAt(Instant.now()); profile.setEmailExpiresAt(Instant.now().plus(Duration.ofHours(24)));
            profiles.saveAndFlush(profile);
            return user.getId();
        });
        // Provider failure after commit must never undo the account or its identifier reservation.
        return new Started(id, false, "EMAIL_REQUIRED", submitEmail(delivery, email, secret));
    }

    static boolean submitEmail(OnboardingDelivery delivery, String email, String secret) {
        try { return delivery.memberEmail(email, secret); }
        catch (RuntimeException unavailable) { return false; }
    }

    public static void cooldown(Instant sent) {
        if (sent != null && Instant.now().isBefore(sent.plusSeconds(60)))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "42930 VERIFICATION_RATE_LIMIT: Try again later");
    }

}
