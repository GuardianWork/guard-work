package com.guardwork.backend.application.repository;

import java.time.Instant;
import java.util.UUID;

/** Minimal database projection for the Candidate Application list. */
public interface CandidateApplicationProjection {
    UUID getId();

    Long getJobId();

    Long getVersion();

    String getJobTitle();

    String getCompanyName();

    String getStatus();

    Instant getAppliedAt();

    String getCvFileName();
}