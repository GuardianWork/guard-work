package com.guardwork.backend.onboarding.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "company_verification_attempts")
public class VerificationAttempt {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "submitter_id")
    private Long submitterId;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "tax_code")
    private String taxCode;

    @Column(name = "work_address")
    private String workAddress;

    @Column(name = "submitter_position")
    private String submitterPosition;

    @Column(name = "representation")
    private String representation;

    @Column(name = "declaration_text")
    private String declarationText;

    @Column(name = "declaration_version")
    private String declarationVersion;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "outcome")
    private String outcome;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "reviewer_id")
    private Long reviewerId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Version
    @Column(name = "version")
    private Long version;

}
