package com.guardwork.backend.auth;

import com.guardwork.backend.user.model.User;

public record UserDto(
        Long id,
        String firstName,
        String lastName,
        String username,
        String email,
        String role
) {
    public static UserDto from(User user) {
        return new UserDto(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getUsername(),
                user.getEmail(),
                user.getRole()
        );
    }
}
