package com.guardwork.backend.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Claims;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(
                "super_secret_test_key_at_least_32_bytes_long_guardwork_2026",
                60000,   // 1 min
                600000   // 10 min
        );
    }

    @Test
    void generateAndParseAccessToken_ValidClaims() {
        String token = jwtService.generateAccessToken(42L, "nguyen_an", "an@example.com", "USER");

        assertThat(token).isNotBlank();
        Claims claims = jwtService.parseAndValidateToken(token);
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("username", String.class)).isEqualTo("nguyen_an");
        assertThat(claims.get("email", String.class)).isEqualTo("an@example.com");
        assertThat(claims.get("role", String.class)).isEqualTo("USER");

        assertThat(jwtService.extractUserId(token)).isEqualTo(42L);
        assertThat(jwtService.extractUsername(token)).isEqualTo("nguyen_an");
        assertThat(jwtService.extractRole(token)).isEqualTo("USER");
    }

    @Test
    void hashToken_ProducesConsistentSha256Hex() {
        String rawToken = "d6e35928-1b6c-48be-81f1-a7fc19b99099";
        String hash1 = jwtService.hashToken(rawToken);
        String hash2 = jwtService.hashToken(rawToken);

        assertThat(hash1).isNotNull().hasSize(64); // 256 bits = 64 hex chars
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void generateRefreshTokenValue_ReturnsUniqueTokens() {
        String token1 = jwtService.generateRefreshTokenValue();
        String token2 = jwtService.generateRefreshTokenValue();

        assertThat(token1).isNotEqualTo(token2);
    }
}
