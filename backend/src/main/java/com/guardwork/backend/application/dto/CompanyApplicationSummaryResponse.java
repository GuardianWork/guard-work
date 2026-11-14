package com.guardwork.backend.application.dto;

import java.time.Instant;
import java.util.UUID;

public record CompanyApplicationSummaryResponse(
        UUID id, String candidateName, String status, Instant appliedAt,
        Long jobId, String jobTitle) {}
