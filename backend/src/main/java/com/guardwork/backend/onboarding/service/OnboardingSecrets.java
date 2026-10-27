package com.guardwork.backend.onboarding.service;

import com.guardwork.backend.user.AccountPhone;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Cryptographic helpers shared by account-email proof and retained Company evidence. */
public final class OnboardingSecrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private OnboardingSecrets() {}

    public static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }

    public static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static boolean matches(String raw, String digest) {
        return raw != null && digest != null && MessageDigest.isEqual(
                hash(raw).getBytes(StandardCharsets.UTF_8), digest.getBytes(StandardCharsets.UTF_8));
    }

    public static String phone(String value) {
        try {
            String phone = AccountPhone.normalize(value);
            if (phone == null) throw invalid("A Vietnamese mobile number is required");
            return phone;
        } catch (IllegalArgumentException invalidPhone) {
            throw invalid("A Vietnamese mobile number is required");
        }
    }

    public static String required(String value, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw invalid("Required field is invalid");
        return value.trim();
    }

    public static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "40030 INVALID_ONBOARDING: " + reason);
    }
}
