package com.guardwork.backend.onboarding.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "company_decision_deliveries")
public class DecisionDelivery {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "recipient_email")
    private String recipientEmail;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "outcome")
    private String outcome;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "attempts")
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Version
    @Column(name = "version")
    private Long version;

}
