package com.guardwork.backend.membership.service;

import com.guardwork.backend.membership.model.MembershipAuditEvent;
import com.guardwork.backend.membership.repository.MembershipAuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipAuditService {
  private final MembershipAuditEventRepository repository;

  public MembershipAuditService(MembershipAuditEventRepository repository) {
    this.repository = repository;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      Long actor,
      Long subject,
      Long company,
      String eventType,
      Long membership,
      Long invitation,
      String reviewCase,
      String reason) {
    MembershipAuditEvent event = new MembershipAuditEvent();
    event.setActorUserId(actor);
    event.setSubjectUserId(subject);
    event.setCompanyId(company);
    event.setEventType(eventType);
    event.setMembershipId(membership);
    event.setInvitationId(invitation);
    event.setReviewCaseReference(reviewCase);
    event.setReason(reason);
    repository.saveAndFlush(event);
  }
}
