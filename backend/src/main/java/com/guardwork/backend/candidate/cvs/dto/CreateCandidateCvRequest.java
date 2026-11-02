package com.guardwork.backend.candidate.cvs.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Registers a CV that has already been uploaded with a signed Cloudinary URL.
 *
 * <p>The candidate is always derived from the authenticated account. {@code candidateId} remains
 * accepted for backward compatibility but must match the authenticated candidate when supplied.
 * File size and content type are read back from Cloudinary; the client-supplied values are only
 * kept so older clients continue to send their existing payloads.</p>
 */
public record CreateCandidateCvRequest(
        UUID candidateId,

        @NotBlank(message = "File name is required")
        @Size(max = 255, message = "File name must be at most 255 characters")
        String fileName,

        @Positive(message = "File size must be greater than zero")
        Long fileSize,

        @Size(max = 100, message = "Content type must be at most 100 characters")
        String contentType,

        @NotBlank(message = "Cloudinary public id is required")
        @Size(max = 255, message = "Cloudinary public id must be at most 255 characters")
        String publicId
) {}
