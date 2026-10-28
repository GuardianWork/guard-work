package com.guardwork.backend.auth;

import java.time.Instant;

public record CurrentUserResponse(
        Long id,
        String firstName,
        String lastName,
        String username,
        String email,
        String role,
        boolean emailVerified,
        boolean recruitingEmailEligible,
        ActiveMembershipDto activeMembership
) {
    public record ActiveMembershipDto(
            Long companyId,
            String companyName,
            String companyRole,
            Instant joinedAt
    ) {
    }
}
