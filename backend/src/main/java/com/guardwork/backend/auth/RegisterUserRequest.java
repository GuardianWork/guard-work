package com.guardwork.backend.auth;

public record RegisterUserRequest(String firstName, String lastName, String username, String email,
        String password) {
}