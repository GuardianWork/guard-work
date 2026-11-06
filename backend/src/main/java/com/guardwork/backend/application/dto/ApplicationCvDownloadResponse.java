package com.guardwork.backend.application.dto;

import java.time.Instant;

public record ApplicationCvDownloadResponse(
        String url,
        Instant expiresAt,
        String fileName
) {}