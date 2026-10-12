package com.guardwork.backend.candidate.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CandidateProfileResponse(
        UUID id,
        Long userId,
        String fullName,
        String avatarUrl,
        String phoneNumber,
        List<String> skills,
        String experienceSummary,
        String educationSummary,
        boolean isPublic,
        Instant createdAt,
        Instant updatedAt
) {}
