package com.guardwork.backend.auth.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.guardwork.backend.auth.model.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Transactional
    @Query("UPDATE RefreshToken r SET r.isRevoked = true, r.revokedAt = :revokedAt WHERE r.id = :id")
    void revokeToken(@Param("id") Long id, @Param("revokedAt") Instant revokedAt);

    @Modifying
    @Transactional
    @Query("UPDATE RefreshToken r SET r.isRevoked = true, r.revokedAt = :revokedAt WHERE r.familyId = :familyId")
    void revokeFamily(@Param("familyId") String familyId, @Param("revokedAt") Instant revokedAt);

    @Modifying
    @Transactional
    @Query("UPDATE RefreshToken r SET r.isRevoked = true, r.revokedAt = :revokedAt WHERE r.userId = :userId")
    void revokeAllForUser(@Param("userId") Long userId, @Param("revokedAt") Instant revokedAt);
}
