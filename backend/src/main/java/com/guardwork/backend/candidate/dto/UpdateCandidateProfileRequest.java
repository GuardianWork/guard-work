package com.guardwork.backend.candidate.dto;

import java.util.List;

import com.guardwork.backend.candidate.util.ValidationConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for updating a candidate profile.
 *
 * Validation rules are defined in {@link com.guardwork.backend.candidate.util.ValidationConstants}.
 */
public record UpdateCandidateProfileRequest(
        @NotBlank(message = "Full name must not be blank")
        @Size(max = 150, message = "Full name must be at most 150 characters")
        String fullName,

        // Validated and normalized at the shared account-contact service boundary.
        String phoneNumber,

        @Pattern(regexp = ValidationConstants.HTTPS_URL_REGEX, message = "Avatar URL must be a valid HTTPS URL")
        @Size(max = 512, message = "Avatar URL must be at most 512 characters")
        String avatarUrl,

        @Size(max = ValidationConstants.MAX_SKILLS, message = "At most {max} skills are allowed")
        List<@Size(min = 1, max = ValidationConstants.MAX_SKILL_LENGTH, message = "Each skill must be non‑blank and at most {max} characters")
        String> skills,

        String experienceSummary,
        String educationSummary,
        Boolean isPublic
) {}
