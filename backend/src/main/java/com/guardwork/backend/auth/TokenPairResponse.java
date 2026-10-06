package com.guardwork.backend.auth;

public record TokenPairResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserDto user
) {
    public static TokenPairResponse of(String accessToken, String refreshToken, long expiresIn, UserDto user) {
        return new TokenPairResponse(accessToken, refreshToken, "Bearer", expiresIn, user);
    }
}
