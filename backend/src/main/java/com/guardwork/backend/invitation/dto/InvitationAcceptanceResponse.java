package com.guardwork.backend.invitation.dto;

import com.guardwork.backend.membership.model.*;
import java.time.Instant;

public record InvitationAcceptanceResponse(
    Long invitationId,
    Long membershipId,
    Long companyId,
    String companyName,
    CompanyRole companyRole,
    MembershipStatus membershipStatus,
    Instant acceptedAt) {}
