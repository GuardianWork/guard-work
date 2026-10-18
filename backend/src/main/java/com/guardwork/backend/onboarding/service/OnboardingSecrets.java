package com.guardwork.backend.onboarding.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class OnboardingSecrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private OnboardingSecrets() {}

    public static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String otp() { return "%06d".formatted(RANDOM.nextInt(1_000_000)); }

    public static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }

    public static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static boolean matches(String raw, String digest) {
        return raw != null && digest != null && MessageDigest.isEqual(
                hash(raw).getBytes(StandardCharsets.UTF_8), digest.getBytes(StandardCharsets.UTF_8));
    }

    public static String otpHash(String pepper, Long userId, String phone, String code) {
        if (pepper == null || pepper.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "50330 VERIFICATION_UNAVAILABLE: Verification is not configured");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((userId + ":" + phone + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String phone(String value) {
        String normalized = required(value, 20);
        if (normalized.matches("0[35789][0-9]{8}")) normalized = "+84" + normalized.substring(1);
        if (!normalized.matches("\\+84[35789][0-9]{8}")) throw invalid("A Vietnamese mobile number is required");
        return normalized;
    }

    public static String required(String value, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw invalid("Required field is invalid");
        return value.trim();
    }

    public static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "40030 INVALID_ONBOARDING: " + reason);
    }
}
