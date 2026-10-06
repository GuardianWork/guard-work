package com.guardwork.backend.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessExpirationMs;
    private final long refreshExpirationMs;

    public JwtService(
            @Value("${guardwork.jwt.secret:guardwork_super_secret_jwt_key_that_is_at_least_32_bytes_long_2026_xyz}") String secret,
            @Value("${guardwork.jwt.access-expiration-ms:900000}") long accessExpirationMs,
            @Value("${guardwork.jwt.refresh-expiration-ms:604800000}") long refreshExpirationMs
    ) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            this.signingKey = Keys.hmacShaKeyFor(padded);
        } else {
            this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        }
        this.accessExpirationMs = accessExpirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public String generateAccessToken(Long userId, String username, String email, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("email", email)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessExpirationMs)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public Claims parseAndValidateToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long extractUserId(String token) {
        Claims claims = parseAndValidateToken(token);
        return Long.parseLong(claims.getSubject());
    }

    public String extractRole(String token) {
        Claims claims = parseAndValidateToken(token);
        return claims.get("role", String.class);
    }

    public String extractUsername(String token) {
        Claims claims = parseAndValidateToken(token);
        return claims.get("username", String.class);
    }

    public String generateRefreshTokenValue() {
        return UUID.randomUUID().toString();
    }

    public String generateFamilyId() {
        return UUID.randomUUID().toString();
    }

    public String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public long getAccessExpirationMs() {
        return accessExpirationMs;
    }

    public long getRefreshExpirationMs() {
        return refreshExpirationMs;
    }
}
