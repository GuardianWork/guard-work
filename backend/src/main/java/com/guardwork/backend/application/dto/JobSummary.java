package com.guardwork.backend.application.dto;

import java.time.Instant;

public record JobSummary(
        Long id,
        String title,
        Long companyId,
        String companyName,
        Instant deadline
) {}
