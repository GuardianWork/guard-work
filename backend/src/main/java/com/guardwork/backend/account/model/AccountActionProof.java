package com.guardwork.backend.account.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name="account_action_proofs") @Getter @Setter
public class AccountActionProof {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="user_id", nullable=false) private Long userId;
    @Column(nullable=false, length=30) private String purpose;
    @Column(nullable=false) private String email;
    @Column(name="secret_hash", length=64) private String secretHash;
    @Column(name="issued_at", nullable=false) private Instant issuedAt;
    @Column(name="expires_at", nullable=false) private Instant expiresAt;
    @Column(name="failed_attempts", nullable=false) private int failedAttempts;
}
