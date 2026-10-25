package com.guardwork.backend.candidate.cvs.dto;

public record UploadUrlRequest(
        String folder,
        String publicId
) {}
