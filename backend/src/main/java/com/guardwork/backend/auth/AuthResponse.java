package com.guardwork.backend.auth;

public record AuthResponse(Long id, String firstName, String lastName, String username, String email) {

    public static AuthResponse from(com.guardwork.backend.user.model.User user) {
        return new AuthResponse(user.getId(), user.getFirstName(), user.getLastName(), user.getUsername(),
                user.getEmail());
    }
}