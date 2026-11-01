package com.guardwork.backend.application.dto;

import java.time.Instant;
import java.util.UUID;

public record JobApplicationSummaryResponse(
        UUID id,
        Long jobId,
        String jobTitle,
        String companyName,
        String status,
        Instant appliedAt,
        String cvFileName
) {}