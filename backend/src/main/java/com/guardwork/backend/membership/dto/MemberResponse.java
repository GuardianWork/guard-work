package com.guardwork.backend.membership.dto;

import com.guardwork.backend.membership.model.*;
import com.guardwork.backend.user.model.User;
import java.time.Instant;

public record MemberResponse(
    Long membershipId,
    Long userId,
    String firstName,
    String lastName,
    String email,
    CompanyRole companyRole,
    MembershipStatus status,
    Instant joinedAt) {
  public static MemberResponse from(CompanyMembership membership, User user) {
    return new MemberResponse(
        membership.getId(),
        membership.getUserId(),
        user.getFirstName(),
        user.getLastName(),
        user.getEmail(),
        membership.getRole(),
        membership.getStatus(),
        membership.getJoinedAt());
  }
}
