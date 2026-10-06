package com.guardwork.backend.auth;

public record RefreshTokenRequest(
        String refreshToken
) {
}
