package com.guardwork.backend.candidate.dto;

import java.util.List;

import com.guardwork.backend.candidate.util.ValidationConstants;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for updating a candidate profile.
 *
 * Validation rules are defined in {@link com.guardwork.backend.candidate.util.ValidationConstants}.
 */
public record UpdateCandidateProfileRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,

        // Validated and normalized at the shared account-contact service boundary.
        String phoneNumber,

        @Pattern(regexp = ValidationConstants.HTTPS_URL_REGEX, message = "Avatar URL must be a valid HTTPS URL")
        @Size(max = 512, message = "Avatar URL must be at most 512 characters")
        String avatarUrl,

        @Size(max = ValidationConstants.MAX_SKILLS, message = "At most {max} skills are allowed")
        List<@Size(max = ValidationConstants.MAX_SKILL_LENGTH, message = "Each skill must be at most {max} characters")
        String> skills,

        List<@Valid ExperienceEntry> experience,

        List<@Valid EducationEntry> education,

        Boolean isPublic
) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported Candidate field");
    }
}
