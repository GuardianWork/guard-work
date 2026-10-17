package com.guardwork.backend.candidate.cvs.dto;

public record UploadUrlResponse(
        String url,
        String apiKey,
        String timestamp,
        String signature,
        String publicId
) {}
