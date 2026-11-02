package com.guardwork.backend.candidate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size; /** Structured experience entry */
public record ExperienceEntry(
        @NotBlank(message = "Company must not be blank")
        @Size(max = 100, message = "Company must be at most 100 characters")
        String company,

        @NotBlank(message = "Role must not be blank")
        @Size(max = 100, message = "Role must be at most 100 characters")
        String role,

        @NotBlank(message = "Start date must not be blank")
        String startDate,

        String endDate,

        @Size(max = 1000, message = "Description must be at most 1000 characters")
        String description
) {}
