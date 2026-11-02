package com.guardwork.backend.staff.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name="account_bans")
@Getter
@Setter
public class AccountBan {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @Column(name="user_id") private Long userId;
  @Column(name="company_id") private Long companyId;
  @Column(nullable=false, length=2000) private String reason;
  @Column(name="started_at", nullable=false) private Instant startedAt;
  @Column(name="expires_at") private Instant expiresAt;
  @Column(name="created_by", nullable=false) private Long createdBy;
  @Column(name="ended_at") private Instant endedAt;
  @Column(name="ended_by") private Long endedBy;
  @Column(name="end_reason", length=2000) private String endReason;

  public boolean activeAt(Instant now) {
    return endedAt == null && (expiresAt == null || expiresAt.isAfter(now));
  }
}
