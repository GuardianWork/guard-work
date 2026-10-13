package com.guardwork.backend.invitation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IssueInvitationRequest(
    @NotBlank
    @Email(regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    @Size(max = 255)
    String email) {}
