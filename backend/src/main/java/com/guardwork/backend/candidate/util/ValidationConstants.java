package com.guardwork.backend.candidate.util;

/**
 * Centralised validation constants used across the candidate profile module.
 * Keeps regex patterns and numeric limits in a single place so they can be
 * referenced from validation annotations and service‑layer checks.
 */
public final class ValidationConstants {
    // Simple HTTPS URL validation – we only require the scheme and a non‑empty host/path
    public static final String HTTPS_URL_REGEX = "^https://.+$";

    // Skill list limits
    public static final int MAX_SKILLS = 30;
    public static final int MAX_SKILL_LENGTH = 50;

    private ValidationConstants() {
        // Prevent instantiation
    }
}
