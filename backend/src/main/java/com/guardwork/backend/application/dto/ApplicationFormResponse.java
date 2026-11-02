package com.guardwork.backend.application.dto;

import java.util.List;
import java.util.UUID;

public record ApplicationFormResponse(
        JobSummary job,
        ProfilePrefill profile,
        List<ApplicationCvOption> cvs,
        UUID defaultCvId,
        List<String> missingProfileFields,
        boolean alreadyApplied
) {}
