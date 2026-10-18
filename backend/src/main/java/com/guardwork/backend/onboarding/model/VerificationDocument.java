package com.guardwork.backend.onboarding.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "company_verification_documents")
public class VerificationDocument {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "uploader_id")
    private Long uploaderId;

    @Column(name = "filename")
    private String filename;

    @Column(name = "media_type")
    private String mediaType;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "content", columnDefinition = "bytea")
    private byte[] content;

    @Column(name = "created_at")
    private Instant createdAt;

}
