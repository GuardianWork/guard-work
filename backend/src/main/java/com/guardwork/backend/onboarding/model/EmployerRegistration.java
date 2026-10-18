package com.guardwork.backend.onboarding.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "employer_registrations")
public class EmployerRegistration {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "continuation_hash")
    private String continuationHash;

    @Column(name = "email")
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "gender")
    private String gender;

    @Column(name = "phone")
    private String phone;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "work_address")
    private String workAddress;

    @Column(name = "email_secret_hash")
    private String emailSecretHash;

    @Column(name = "email_expires_at")
    private Instant emailExpiresAt;

    @Column(name = "email_sent_at")
    private Instant emailSentAt;

    @Column(name = "promoted_user_id")
    private Long promotedUserId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Version
    @Column(name = "version")
    private Long version;

}
