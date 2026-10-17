package com.guardwork.backend.candidate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.guardwork.backend.candidate.util.SkillsJsonConverter;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
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

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Convert(converter = SkillsJsonConverter.class)
    @Column(name = "skills", columnDefinition = "jsonb", nullable = false)
    private List<String> skills = new ArrayList<>();

    @Column(name = "experience_summary", columnDefinition = "TEXT")
    private String experienceSummary;

    @Column(name = "education_summary", columnDefinition = "TEXT")
    private String educationSummary;

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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
