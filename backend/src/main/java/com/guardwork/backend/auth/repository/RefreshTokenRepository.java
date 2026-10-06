package com.guardwork.backend.auth.repository;

import java.time.Instant;
import java.util.Optional;

import com.guardwork.backend.auth.model.RefreshToken;

public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken refreshToken);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    void revokeToken(Long id, Instant revokedAt);

    void revokeFamily(String familyId, Instant revokedAt);

    void revokeAllForUser(Long userId, Instant revokedAt);
}
