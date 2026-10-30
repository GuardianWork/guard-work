package com.guardwork.backend.application.dto;

import java.util.UUID;

public record ApplicationCvOption(
        UUID id,
        String fileName,
        String contentType,
        long fileSize,
        boolean isDefault
) {}
