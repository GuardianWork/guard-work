package com.guardwork.backend.db.migration;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.guardwork.backend.auth.model.PasswordResetToken;
import com.guardwork.backend.auth.model.RefreshToken;
import com.guardwork.backend.auth.repository.PasswordResetTokenRepository;
import com.guardwork.backend.auth.repository.RefreshTokenRepository;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MigrationV3Test {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Test
    void testUserRoleAndStatusPersistence() {
        String uniqueUsername = "v3_user_" + System.currentTimeMillis();
        String uniqueEmail = uniqueUsername + "@guardwork.vn";

        User user = new User();
        user.setFirstName("V3");
        user.setLastName("Tester");
        user.setUsername(uniqueUsername);
        user.setEmail(uniqueEmail);
        user.setPassword("secretHash123");
        user.setRole("ADMIN");
        user.setStatus("ACTIVE");
        user.setEmailVerified(true);

        User saved = userRepository.save(user);
        assertThat(saved.getId()).isNotNull();

        User reloaded = userRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo("ADMIN");
        assertThat(reloaded.getStatus()).isEqualTo("ACTIVE");
        assertThat(reloaded.isEmailVerified()).isTrue();
    }

    @Test
    void testRefreshTokenLifecycleAndRevocation() {
        String uniqueUsername = "rf_user_" + System.currentTimeMillis();
        User user = new User();
        user.setFirstName("RF");
        user.setLastName("User");
        user.setUsername(uniqueUsername);
        user.setEmail(uniqueUsername + "@guardwork.vn");
        user.setPassword("pass");
        User savedUser = userRepository.save(user);

        RefreshToken token = new RefreshToken();
        token.setUserId(savedUser.getId());
        token.setTokenHash("hash_" + System.currentTimeMillis());
        token.setFamilyId("family_" + System.currentTimeMillis());
        token.setRevoked(false);
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        token.setIpAddress("127.0.0.1");
        token.setUserAgent("JUnit");

        RefreshToken savedToken = refreshTokenRepository.save(token);
        assertThat(savedToken.getId()).isNotNull();

        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHash(token.getTokenHash());
        assertThat(found).isPresent();
        assertThat(found.get().isRevoked()).isFalse();

        // Revoke token
        refreshTokenRepository.revokeToken(savedToken.getId(), Instant.now());
        RefreshToken revoked = refreshTokenRepository.findByTokenHash(token.getTokenHash()).orElseThrow();
        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.getRevokedAt()).isNotNull();
    }

    @Test
    void testPasswordResetTokenLifecycle() {
        String uniqueUsername = "pwd_user_" + System.currentTimeMillis();
        User user = new User();
        user.setFirstName("PWD");
        user.setLastName("User");
        user.setUsername(uniqueUsername);
        user.setEmail(uniqueUsername + "@guardwork.vn");
        user.setPassword("pass");
        User savedUser = userRepository.save(user);

        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setUserId(savedUser.getId());
        resetToken.setTokenHash("reset_hash_" + System.currentTimeMillis());
        resetToken.setConsumed(false);
        resetToken.setExpiresAt(Instant.now().plusSeconds(900));

        PasswordResetToken saved = passwordResetTokenRepository.save(resetToken);
        assertThat(saved.getId()).isNotNull();

        PasswordResetToken found = passwordResetTokenRepository.findByTokenHash(resetToken.getTokenHash()).orElseThrow();
        assertThat(found.isConsumed()).isFalse();

        passwordResetTokenRepository.markConsumed(saved.getId());
        PasswordResetToken consumed = passwordResetTokenRepository.findByTokenHash(resetToken.getTokenHash()).orElseThrow();
        assertThat(consumed.isConsumed()).isTrue();
    }
}
