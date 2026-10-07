package com.guardwork.backend.auth;

public record CurrentUserResponse(
        Long id,
        String firstName,
        String lastName,
        String username,
        String email,
        String role,
        ActiveMembershipDto activeMembership
) {
    public record ActiveMembershipDto(
            Long companyId,
            String companyName,
            String companyRole
    ) {
    }
}
