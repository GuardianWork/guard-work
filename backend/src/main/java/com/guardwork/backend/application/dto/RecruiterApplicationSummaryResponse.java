package com.guardwork.backend.application.dto;

import java.time.Instant;
import java.util.UUID;

/** The private Company listing deliberately exposes only submitted identity and status. */
public record RecruiterApplicationSummaryResponse(
        UUID id,
        String candidateName,
        String status,
        Instant appliedAt) {}
