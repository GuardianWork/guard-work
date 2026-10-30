package com.guardwork.backend.onboarding.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

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
}
