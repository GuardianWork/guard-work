package com.guardwork.backend.candidate.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.guardwork.backend.candidate.dto.ExperienceEntry;
import com.guardwork.backend.candidate.dto.EducationEntry;

public record CandidateProfileResponse(
        UUID id,
        Long userId,
        String email,
        String fullName,
        String firstName,
        String lastName,
        String avatarUrl,
        String phoneNumber,
        List<String> skills,
        List<ExperienceEntry> experience,
        List<EducationEntry> education,
        boolean isPublic,
        Instant createdAt,
        Instant updatedAt
) {}
