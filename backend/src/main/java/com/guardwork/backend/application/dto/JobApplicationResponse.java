package com.guardwork.backend.application.dto;

import java.time.Instant;
import java.util.UUID;

public record JobApplicationResponse(
        UUID id,
        Long jobId,
        String jobTitle,
        Long companyId,
        String companyName,
        String status,
        Instant appliedAt,
        String cvFileName
) {}
