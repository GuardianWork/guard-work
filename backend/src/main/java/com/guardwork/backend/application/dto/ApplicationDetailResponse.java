package com.guardwork.backend.application.dto;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

public record ApplicationDetailResponse(
        UUID id,
        Long jobId,
        String candidateName,
        String email,
        String phoneNumber,
        String coverLetter,
        String jobTitle,
        String companyName,
        String status,
        long version,
        Instant appliedAt,
        String cvFileName,
        String cvContentType,
        long cvFileSize,
        List<ApplicationStatusHistoryResponse> history,
        String candidateFeedback) {
}
