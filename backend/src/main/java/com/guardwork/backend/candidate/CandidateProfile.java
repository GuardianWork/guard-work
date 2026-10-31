package com.guardwork.backend.candidate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.guardwork.backend.candidate.dto.EducationEntry;
import com.guardwork.backend.candidate.dto.ExperienceEntry;
import com.guardwork.backend.candidate.util.SkillsJsonConverter;
import com.guardwork.backend.candidate.util.ExperienceJsonConverter;
import com.guardwork.backend.candidate.util.EducationJsonConverter;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "candidate_profiles")
public class CandidateProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /**
     * Derived from the owning account and never persisted on the profile.
     * Keeping a single source of truth avoids the profile and login email drifting apart.
     */
    @Transient
    private String email;

    // Projection only: User owns personal identity; no duplicate profile-name storage.
    @Transient
    private String fullName;

    @Transient
    private String firstName;

    @Transient
    private String lastName;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    // Historical contact only; current contact is User.phoneNumber.
    @Column(name = "phone_number", length = 20, insertable = false, updatable = false)
    private String phoneNumber;

    @Convert(converter = SkillsJsonConverter.class)
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    @Column(name = "skills", columnDefinition = "jsonb", nullable = false)
    private List<String> skills = new ArrayList<>();

    @Convert(converter = ExperienceJsonConverter.class)
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    @Column(name = "experience", columnDefinition = "jsonb")
    private List<ExperienceEntry> experience = new ArrayList<>();

    @Convert(converter = EducationJsonConverter.class)
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    @Column(name = "education", columnDefinition = "jsonb")
    private List<EducationEntry> education = new ArrayList<>();

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
        if (skills == null) {
            skills = new ArrayList<>();
        }
        if (experience == null) {
            experience = new ArrayList<>();
        }
        if (education == null) {
            education = new ArrayList<>();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
