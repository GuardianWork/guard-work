package com.guardwork.backend.onboarding.service;

import com.guardwork.backend.onboarding.model.EmployerProfile;
import java.util.Locale;

/** Actual mailbox proof is scoped to an account's current normalized email. */
public final class EmailProof {
    private EmailProof() {}

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean verified(EmployerProfile profile, String currentEmail) {
        String email = normalize(currentEmail);
        return profile != null && profile.getEmailVerifiedAt() != null && email != null
                && email.equals(profile.getEmailVerifiedEmail());
    }
}
