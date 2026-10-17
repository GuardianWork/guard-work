package com.guardwork.backend.membership.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "company_memberships")
public class CompanyMembership {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "company_id", nullable = false)
  private Long companyId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private CompanyRole role;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private MembershipStatus status;

  @Column(name = "joined_at", nullable = false)
  private Instant joinedAt;

  @Column(name = "ended_at")
  private Instant endedAt;

  @Column(name = "ended_by_user_id")
  private Long endedByUserId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = Instant.now();
    if (joinedAt == null) joinedAt = createdAt;
  }
}
