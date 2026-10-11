package com.guardwork.backend.membership.repository;

import com.guardwork.backend.membership.model.MembershipAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipAuditEventRepository extends JpaRepository<MembershipAuditEvent, Long> {
  java.util.Optional<MembershipAuditEvent> findByReviewCaseReferenceAndEventType(
      String reference, String type);
}
