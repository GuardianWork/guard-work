package com.guardwork.backend.onboarding.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "employer_profiles")
public class EmployerProfile {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "applicant")
    private boolean applicant;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "gender")
    private String gender;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "email_verified_email")
    private String emailVerifiedEmail;

    @Column(name = "email_challenge_email")
    private String emailChallengeEmail;

    // Historical contact only; current contact is User.phoneNumber.
    @Column(name = "phone", insertable = false, updatable = false)
    private String phone;

    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

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

    @Column(name = "phone_secret_hash")
    private String phoneSecretHash;

    @Column(name = "phone_expires_at")
    private Instant phoneExpiresAt;

    @Column(name = "phone_sent_at")
    private Instant phoneSentAt;

    @Column(name = "phone_failures")
    private int phoneFailures;

    @Column(name = "phone_delivered")
    private boolean phoneDelivered;

    @Version
    @Column(name = "version")
    private Long version;

}
