package com.guardwork.backend.candidate.cvs.dto;

import java.time.Instant;
import java.util.UUID;

public record CandidateCvResponse(
        UUID id,
        UUID candidateId,
        String fileName,
        Long fileSize,
        String contentType,
        String publicId,
        boolean isDefault,
        Instant createdAt,
        Instant updatedAt
) {}
