package com.guardwork.backend.candidate.cvs.dto;

import java.util.UUID;

public record CreateCandidateCvRequest(
        UUID candidateId,
        String fileName,
        Long fileSize,
        String contentType,
        String publicId
) {}
