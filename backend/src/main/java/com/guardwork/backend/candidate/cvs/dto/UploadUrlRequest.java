package com.guardwork.backend.candidate.cvs.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * Requests a signed Cloudinary upload URL.
 *
 * <p>{@code folder} and {@code publicId} are retained for client compatibility but are ignored:
 * the server derives both from the authenticated candidate so a caller cannot choose where a CV
 * is stored. The privacy confirmation is mandatory before an upload may start.</p>
 */
public record UploadUrlRequest(
        @NotNull(message = "Privacy consent is required before uploading a CV")
        @AssertTrue(message = "Privacy consent is required before uploading a CV")
        Boolean consentGiven,

        String folder,

        String publicId
) {}
