package com.guardwork.backend.candidate.cvs;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "candidate_cvs")
public class CandidateCv {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "public_id", nullable = false, length = 255)
    private String publicId;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Set when the Candidate removes the CV from their library. The row stays as an audit
     * tombstone so access grants keep a valid reference and storage cleanup can be retried.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Set once the Cloudinary object no longer exists; null after deletion means purge is pending. */
    @Column(name = "storage_deleted_at")
    private Instant storageDeletedAt;

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
