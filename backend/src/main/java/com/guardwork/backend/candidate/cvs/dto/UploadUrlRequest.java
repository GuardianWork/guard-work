package com.guardwork.backend.candidate.cvs.dto;

/**
 * Requests a signed Cloudinary upload URL.
 *
 * <p>Consent is collected by the frontend. The backend keeps {@code consentGiven} only
 * for backward compatibility and no longer validates or enforces it.</p>
 */
public record UploadUrlRequest(
        Boolean consentGiven,

        String folder,

        String publicId
) {}