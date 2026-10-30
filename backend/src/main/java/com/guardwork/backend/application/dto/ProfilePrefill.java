package com.guardwork.backend.application.dto;

public record ProfilePrefill(
        String fullName,
        String email,
        String phoneNumber
) {}
