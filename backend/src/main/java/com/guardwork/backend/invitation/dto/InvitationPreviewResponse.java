package com.guardwork.backend.invitation.dto;

import com.guardwork.backend.membership.model.CompanyRole;
import java.time.Instant;

public record InvitationPreviewResponse(
    Long invitationId,
    Long companyId,
    String companyName,
    CompanyRole companyRole,
    Instant expiresAt) {}
