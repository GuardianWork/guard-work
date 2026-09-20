package com.guardwork.backend.auth;

public record LoginRequest(String usernameOrEmail, String password) {
}