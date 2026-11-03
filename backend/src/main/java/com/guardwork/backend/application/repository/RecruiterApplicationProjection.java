package com.guardwork.backend.application.repository;

import java.time.Instant;
import java.util.UUID;

/** Minimal database projection for the Company Application list. */
public interface RecruiterApplicationProjection {
    UUID getId();

    String getCandidateName();

    String getStatus();

    Instant getAppliedAt();
}
