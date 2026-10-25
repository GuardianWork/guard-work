package com.guardwork.backend.invitation.dto;

import com.guardwork.backend.invitation.model.InvitationStatus;
import java.time.Instant;

public record InvitationDeclineResponse(
    Long invitationId, InvitationStatus status, Instant decidedAt) {}
