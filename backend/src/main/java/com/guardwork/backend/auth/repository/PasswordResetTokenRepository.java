package com.guardwork.backend.auth.repository;

import java.util.Optional;

import com.guardwork.backend.auth.model.PasswordResetToken;

public interface PasswordResetTokenRepository {

    PasswordResetToken save(PasswordResetToken token);

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    void markConsumed(Long id);
}
