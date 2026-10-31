package com.guardwork.backend.account.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name="account_deletion_requests") @Getter @Setter
public class AccountDeletionRequest {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="user_id", nullable=false) private Long userId;
    @Column(name="requested_at", nullable=false) private Instant requestedAt;
    @Column(name="cancelled_at") private Instant cancelledAt;
}
