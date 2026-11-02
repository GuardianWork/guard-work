package com.guardwork.backend.application.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable copy of what the candidate submitted. It must not depend on the candidate's mutable
 * profile, the job's mutable company name, or the CV row in the candidate's library.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "application_snapshots")
public class ApplicationSnapshot {

    @Id
    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "phone_number", nullable = false, length = 10)
    private String phoneNumber;

    @Column(name = "cover_letter", length = 1000)
    private String coverLetter;

    @Column(name = "job_title", nullable = false, length = 200)
    private String jobTitle;

    @Column(name = "company_name", nullable = false, length = 255)
    private String companyName;

    @Column(name = "source_cv_id")
    private UUID sourceCvId;

    @Column(name = "cv_storage_key", nullable = false, length = 255)
    private String cvStorageKey;

    @Column(name = "cv_storage_provider", nullable = false, length = 30)
    private String cvStorageProvider = "CLOUDINARY";

    @Column(name = "cv_file_name", nullable = false, length = 255)
    private String cvFileName;

    @Column(name = "cv_content_type", nullable = false, length = 100)
    private String cvContentType;

    @Column(name = "cv_file_size", nullable = false)
    private long cvFileSize;

    @Column(name = "snapshot_schema_version", nullable = false)
    private int snapshotSchemaVersion = 1;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;
}
