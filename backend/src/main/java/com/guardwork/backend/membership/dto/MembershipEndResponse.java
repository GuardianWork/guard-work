package com.guardwork.backend.membership.dto;

import com.guardwork.backend.membership.model.*;
import java.time.Instant;

public record MembershipEndResponse(Long membershipId, MembershipStatus status, Instant endedAt) {
  public static MembershipEndResponse from(CompanyMembership membership) {
    return new MembershipEndResponse(
        membership.getId(), membership.getStatus(), membership.getEndedAt());
  }
}
