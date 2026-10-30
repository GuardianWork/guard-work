package com.guardwork.backend.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "user_email_verification")
public class UserEmailVerification {
    @Id @Column(name = "user_id")
    private Long userId;
    @Column(nullable = false, length = 255)
    private String email;
    @Column(name = "secret_hash", length = 64)
    private String secretHash;
    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;
    @Column(name = "consumed_at")
    private Instant consumedAt;
}
