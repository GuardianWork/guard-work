package com.guardwork.backend.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SubmitApplicationRequest(
        @NotNull @Positive Long jobId,
        @NotBlank @Size(max = 150) String fullName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Pattern(regexp = "^0[0-9]{9}$") String phoneNumber,
        @Size(max = 1000) String coverLetter,
        @NotNull UUID cvId
) {}
