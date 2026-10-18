package com.guardwork.backend.onboarding.service;

import com.guardwork.backend.company.model.VerificationStatus;
import com.guardwork.backend.membership.service.CompanyAccessService;
import com.guardwork.backend.onboarding.delivery.OnboardingDelivery;
import com.guardwork.backend.onboarding.dto.OnboardingRequests.*;
import com.guardwork.backend.onboarding.model.EmployerProfile;
import com.guardwork.backend.onboarding.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PersonalVerificationService {
    private final EmployerProfileRepository profiles;
    private final CompanyAccessService access;
    private final OnboardingDelivery delivery;
    private final TransactionTemplate tx;
    private final String pepper;

    public PersonalVerificationService(EmployerProfileRepository profiles, CompanyAccessService access,
            OnboardingDelivery delivery, PlatformTransactionManager manager,
            @Value("${ONBOARDING_OTP_SECRET:}") String pepper) {
        this.profiles = profiles; this.access = access; this.delivery = delivery;
        this.tx = new TransactionTemplate(manager); this.pepper = pepper;
    }

    public record State(String fullName, String gender, String phone, boolean emailVerified, boolean phoneVerified,
                        String companyName, String workAddress, Long version, String stage, boolean applicant, String companyRole) {}

    public State state(Long actor) {
        return tx.execute(status -> {
            var p = enrolled(actor);
            var company = access.findCurrentCompanyAccess(actor);
            String stage = p.getEmailVerifiedAt() == null ? "EMAIL_REQUIRED"
                    : p.getPhoneVerifiedAt() == null ? "PHONE_REQUIRED" : "LEGAL_REQUIRED";
            if (p.getPhoneVerifiedAt() != null && company != null) stage = company.company().getVerificationStatus().name();
            return new State(p.getFullName(), p.getGender(), p.getPhone(), p.getEmailVerifiedAt() != null,
                    p.getPhoneVerifiedAt() != null, p.getCompanyName(), p.getWorkAddress(), p.getVersion(), stage, p.isApplicant(),
                    company == null ? null : company.membership().getRole().name());
        });
    }

    public boolean sendMemberEmail(Long actor) {
        String secret = OnboardingSecrets.token();
        String email = tx.execute(status -> {
            var user = access.requireActiveUser(actor);
            var p = enrolled(actor);
            if (p.getEmailVerifiedAt() != null) throw OnboardingSecrets.invalid("Email is already verified");
            EmployerRegistrationService.cooldown(p.getEmailSentAt());
            p.setEmailSecretHash(OnboardingSecrets.hash(secret)); p.setEmailSentAt(Instant.now());
            p.setEmailExpiresAt(Instant.now().plus(Duration.ofHours(24))); profiles.saveAndFlush(p);
            return user.getEmail();
        });
        return delivery.memberEmail(email, secret);
    }

    public void verifyMemberEmail(Long actor, EmailSecret proof) {
        tx.executeWithoutResult(status -> {
            var p = enrolled(actor);
            if (proof == null || !OnboardingSecrets.matches(proof.emailSecret(), p.getEmailSecretHash())
                    || p.getEmailExpiresAt() == null || !Instant.now().isBefore(p.getEmailExpiresAt()))
                throw OnboardingSecrets.invalid("Invalid or expired email proof");
            p.setEmailVerifiedAt(Instant.now()); p.setEmailSecretHash(null); profiles.saveAndFlush(p);
        });
    }

    public void sendPhone(Long actor, Phone request) {
        String phone = OnboardingSecrets.phone(request == null ? null : request.phone());
        String code = OnboardingSecrets.otp();
        String hash = OnboardingSecrets.otpHash(pepper, actor, phone, code);
        tx.executeWithoutResult(status -> {
            var p = enrolled(actor);
            requireEmail(p);
            if (p.getPhoneVerifiedAt() != null) throw OnboardingSecrets.invalid("Verified phone changes require recovery");
            EmployerRegistrationService.cooldown(p.getPhoneSentAt());
            if (profiles.existsByPhoneAndPhoneVerifiedAtIsNotNull(phone)) throw unavailable();
            p.setPhone(phone); p.setPhoneSecretHash(hash); p.setPhoneSentAt(Instant.now());
            p.setPhoneExpiresAt(Instant.now().plusSeconds(300)); p.setPhoneFailures(0); p.setPhoneDelivered(false);
            profiles.saveAndFlush(p);
        });
        boolean accepted = delivery.sms(phone, code);
        tx.executeWithoutResult(status -> {
            var p = enrolled(actor);
            if (hash.equals(p.getPhoneSecretHash())) { p.setPhoneDelivered(accepted); profiles.saveAndFlush(p); }
        });
        if (!accepted) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "50330 VERIFICATION_UNAVAILABLE: SMS delivery is unavailable");
    }

    public void verifyPhone(Long actor, Code proof) {
        boolean correct = Boolean.TRUE.equals(tx.execute(status -> {
            var p = enrolled(actor);
            requireEmail(p);
            if (p.getPhoneVerifiedAt() != null) return true;
            if (!p.isPhoneDelivered() || p.getPhoneExpiresAt() == null || !Instant.now().isBefore(p.getPhoneExpiresAt())
                    || p.getPhoneFailures() >= 5) return false;
            String code = proof == null || proof.code() == null ? "" : proof.code();
            String hash = OnboardingSecrets.otpHash(pepper, actor, p.getPhone(), code);
            if (!MessageDigest.isEqual(hash.getBytes(StandardCharsets.UTF_8), p.getPhoneSecretHash().getBytes(StandardCharsets.UTF_8))) {
                p.setPhoneFailures(p.getPhoneFailures() + 1); profiles.saveAndFlush(p); return false;
            }
            if (profiles.existsByPhoneAndPhoneVerifiedAtIsNotNull(p.getPhone())) throw unavailable();
            p.setPhoneVerifiedAt(Instant.now()); p.setPhoneSecretHash(null); p.setPhoneDelivered(false); profiles.saveAndFlush(p);
            return true;
        }));
        // Failed-attempt updates must commit before the error is returned.
        if (!correct) throw OnboardingSecrets.invalid("Invalid, exhausted or expired OTP");
    }

    public void updateCompany(Long actor, CompanyDraft draft) {
        tx.executeWithoutResult(status -> {
            var p = requirePersonal(actor);
            if (access.findCurrentCompanyAccess(actor) != null) throw OnboardingSecrets.invalid("Use the submitted Company dossier");
            if (draft == null || draft.expectedVersion() == null || !draft.expectedVersion().equals(p.getVersion()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "40022 CONCURRENT_MODIFICATION: Reload current information");
            p.setCompanyName(OnboardingSecrets.required(draft.companyName(), 255));
            p.setWorkAddress(OnboardingSecrets.required(draft.workAddress(), 1000)); profiles.saveAndFlush(p);
        });
    }

    public EmployerProfile requirePersonal(Long actor) {
        var p = enrolled(actor); requireEmail(p);
        if (p.getPhoneVerifiedAt() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "40330 PERSONAL_VERIFICATION_REQUIRED: Verify your phone first");
        return p;
    }

    private EmployerProfile enrolled(Long actor) {
        var user = access.requireActiveUser(actor);
        var existing = profiles.findLocked(actor);
        if (existing.isPresent()) return existing.get();
        if (access.findCurrentCompanyAccess(actor) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "40331 ONBOARDING_UNAVAILABLE: Existing account conversion is not available");
        EmployerProfile p = new EmployerProfile(); p.setUserId(actor); p.setApplicant(false);
        p.setFullName((user.getFirstName() + " " + user.getLastName()).trim());
        return profiles.saveAndFlush(p);
    }

    private void requireEmail(EmployerProfile p) {
        if (p.getEmailVerifiedAt() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "40330 PERSONAL_VERIFICATION_REQUIRED: Verify your email first");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "40930 IDENTIFIER_UNAVAILABLE: Phone is unavailable");
    }
}
