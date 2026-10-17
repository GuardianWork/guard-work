package com.guardwork.backend.invitation.dto;

import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.invitation.model.*;
import com.guardwork.backend.membership.model.CompanyRole;
import java.time.Instant;

public record OwnerInvitationResponse(
    Long id,
    String email,
    Long companyId,
    String companyName,
    CompanyRole companyRole,
    InvitationStatus status,
    Instant issuedAt,
    Instant expiresAt,
    DeliveryStatus deliveryStatus) {
  public static OwnerInvitationResponse from(CompanyInvitation invitation, Company company) {
    return new OwnerInvitationResponse(
        invitation.getId(),
        invitation.getRecipientEmailNormalized(),
        company.getId(),
        company.getName(),
        invitation.getRole(),
        invitation.getStatus(),
        invitation.getIssuedAt(),
        invitation.getExpiresAt(),
        invitation.getDeliveryStatus());
  }
}
