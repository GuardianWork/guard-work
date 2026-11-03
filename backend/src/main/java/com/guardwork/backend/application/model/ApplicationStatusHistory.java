package com.guardwork.backend.application.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "application_status_history")
public class ApplicationStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "from_status", length = 30)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 30)
    private String toStatus;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "rejection_reason", length = 40)
    private String rejectionReason;

    @Column(name = "internal_explanation", length = 1000)
    private String internalExplanation;

    @Column(name = "candidate_feedback", length = 1000)
    private String candidateFeedback;
}
