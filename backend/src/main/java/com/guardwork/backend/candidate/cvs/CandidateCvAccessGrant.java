package com.guardwork.backend.candidate.cvs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Explicit permission for one Company to read one Candidate CV for one Application.
 *
 * <p>The grant is the only authorization source for Company CV reads. It deliberately
 * references the Candidate's library object instead of an application-specific copy, so
 * removing the CV from the library does not erase what the receiving Company already
 * received; it only starts a bounded review window.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "candidate_cv_access_grants")
public class CandidateCvAccessGrant {

    public static final String SCOPE_APPLICATION_REVIEW = "APPLICATION_REVIEW";
    public static final String PROVIDER_CLOUDINARY = "CLOUDINARY";
    public static final String REASON_APPLICATION_CLOSED = "APPLICATION_CLOSED";
    public static final String REASON_APPLICATION_INACTIVE = "APPLICATION_INACTIVE";
    public static final String REASON_CANDIDATE_CV_DELETED = "CANDIDATE_CV_DELETED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    /** Access epoch; reactivation after INACTIVE starts a new epoch. */
    @Column(name = "epoch", nullable = false)
    private int epoch = 1;

    /** Null only for a legacy grant whose Candidate CV row was removed by a later approved purge. */
    @Column(name = "cv_id")
    private UUID cvId;

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "scope", nullable = false, length = 30)
    private String scope = SCOPE_APPLICATION_REVIEW;

    @Column(name = "storage_provider", nullable = false, length = 30)
    private String storageProvider = PROVIDER_CLOUDINARY;

    @Column(name = "storage_key", nullable = false, length = 255)
    private String storageKey;

    @Column(name = "resource_type", nullable = false, length = 20)
    private String resourceType;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    /** Null while the Candidate keeps the CV in the library. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 30)
    private String revokedReason;

    public boolean isLive(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }
}
