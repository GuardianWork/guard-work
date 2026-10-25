package com.guardwork.backend.candidate.util;

/**
 * Utility class for masking personal contact information when it is exposed to
 * unverified employers. The masking follows the Vietnamese privacy regulations
 * (Law 91/2025/QH15).
 */
public final class ContactMaskingUtils {
    private ContactMaskingUtils() {
        // Prevent instantiation
    }

    /**
     * Masks an email address by keeping the first three characters (or the full
     * prefix if it is shorter) and replacing the rest of the local‑part with
     * three asterisks.
     *
     * Example: "nguyenvana@gmail.com" → "ngu***@gmail.com"
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int atIdx = email.indexOf('@');
        if (atIdx <= 0) {
            return email; // malformed, return as‑is
        }
        String prefix = email.substring(0, Math.min(3, atIdx));
        String domain = email.substring(atIdx);
        return prefix + "***" + domain;
    }

    /**
     * Masks a Vietnamese phone number. It keeps the first four digits and the
     * last two digits, replacing the middle three digits with asterisks.
     *
     * Example: "0912345678" → "0912***78"
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        // Keep only digits for masking logic, preserve any leading '+' if present
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 6) {
            return phone; // not enough digits to mask meaningfully
        }
        String start = digits.substring(0, 4);
        String end = digits.substring(digits.length() - 2);
        return start + "***" + end;
    }
}
