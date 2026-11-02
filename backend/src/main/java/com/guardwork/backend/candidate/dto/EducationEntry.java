package com.guardwork.backend.candidate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size; /** Structured education entry */
public record EducationEntry(
        @NotBlank(message = "Institution must not be blank")
        @Size(max = 150, message = "Institution must be at most 150 characters")
        String institution,

        @NotBlank(message = "Degree must not be blank")
        @Size(max = 100, message = "Degree must be at most 100 characters")
        String degree,

        @NotBlank(message = "Start year must not be blank")
        String startYear,

        String endYear,

        @Size(max = 1000, message = "Description must be at most 1000 characters")
        String description
) {}
