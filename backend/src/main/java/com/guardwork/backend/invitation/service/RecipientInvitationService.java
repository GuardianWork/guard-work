package com.guardwork.backend.invitation.service;

import com.guardwork.backend.auth.service.AccountEmailVerificationService;
import com.guardwork.backend.company.model.*;
import com.guardwork.backend.onboarding.repository.EmployerProfileRepository;
import com.guardwork.backend.onboarding.service.EmailProof;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.invitation.dto.*;
import com.guardwork.backend.invitation.model.*;
import com.guardwork.backend.invitation.repository.CompanyInvitationRepository;
import com.guardwork.backend.membership.model.*;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.membership.service.*;
import com.guardwork.backend.notification.service.*;
import com.guardwork.backend.user.model.User;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecipientInvitationService {
  private final CompanyAccessService access;
  private final EmployerProfileRepository profiles;
  private final CompanyRepository companies;
  private final CompanyInvitationRepository invitations;
  private final CompanyMembershipRepository memberships;
  private final MembershipAuditService audit;
  private final NotificationIntentService notifications;
  private final NotificationDispatcher dispatcher;
  private final Clock clock;
  private final Validator validator;
  private final TransactionTemplate transaction;

  public RecipientInvitationService(
      CompanyAccessService access,
      EmployerProfileRepository profiles,
      CompanyRepository companies,
      CompanyInvitationRepository invitations,
      CompanyMembershipRepository memberships,
      MembershipAuditService audit,
      NotificationIntentService notifications,
      NotificationDispatcher dispatcher,
      Clock clock,
      PlatformTransactionManager manager,
      Validator validator) {
    this.access = access;
    this.profiles = profiles;
    this.companies = companies;
    this.invitations = invitations;
    this.memberships = memberships;
    this.audit = audit;
    this.notifications = notifications;
    this.dispatcher = dispatcher;
    this.clock = clock;
    this.validator = validator;
    transaction = new TransactionTemplate(manager);
  }

  public InvitationPreviewResponse preview(Long actor, RecipientInvitationRequest request) {
    var candidate = candidate(request);
    return transaction.execute(
        tx -> {
          var locked = lock(actor, candidate, request.secret());
          requirePending(locked.invitation());
          var i = locked.invitation();
          var c = locked.company();
          return new InvitationPreviewResponse(
              i.getId(), c.getId(), c.getName(), i.getRole(), i.getExpiresAt());
        });
  }

  public Acceptance accept(Long actor, RecipientInvitationRequest request) {
    var candidate = candidate(request);
    var result =
        transaction.execute(
            tx -> {
              var locked = lock(actor, candidate, request.secret());
              var i = locked.invitation();
              var c = locked.company();
              // The receipt is historical; retries never re-admit a departed/removed recipient.
              if (i.getStatus() == InvitationStatus.ACCEPTED
                  && actor.equals(i.getAcceptedByUserId())) {
                requireConfirmation(request);
                var membership =
                    memberships
                        .findById(i.getAcceptedMembershipId())
                        .orElseThrow(RecipientInvitationService::unavailable);
                return new Acceptance(receipt(i, c, membership), false, null);
              }
              // Check only enrolled accounts; do not enroll ordinary legacy recipients.
              profiles.findById(actor).ifPresent(profile -> {
                if (!EmailProof.verified(profile, locked.user().getEmail()))
                  throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                      "40330 PERSONAL_VERIFICATION_REQUIRED: Verify your email first");
              });
              requirePending(i);
              requireEligible(c);
              AccountEmailVerificationService.requireRecruitingEmailEligible(locked.user());
              requireConfirmation(request);
              if (memberships.findByUserIdAndStatus(actor, MembershipStatus.ACTIVE).isPresent())
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "40910 ACTIVE_COMPANY_MEMBERSHIP_EXISTS: Active Company membership already"
                        + " exists");
              Instant now = clock.instant();
              var membership = new CompanyMembership();
              membership.setUserId(actor);
              membership.setCompanyId(c.getId());
              membership.setRole(CompanyRole.RECRUITER);
              membership.setStatus(MembershipStatus.ACTIVE);
              membership.setJoinedAt(now);
              membership.setCreatedAt(now);
              memberships.saveAndFlush(membership);
              i.setStatus(InvitationStatus.ACCEPTED);
              i.setAcceptedByUserId(actor);
              i.setAcceptedMembershipId(membership.getId());
              i.setDecidedByUserId(actor);
              i.setDecidedAt(now);
              i.setUpdatedAt(now);
              invitations.saveAndFlush(i);
              audit.record(
                  actor,
                  actor,
                  c.getId(),
                  "INVITATION_ACCEPTED",
                  membership.getId(),
                  i.getId(),
                  null,
                  null);
              UUID event = notifications.record(c.getOwnerUserId(), "RECRUITER_ACCEPTED", c, now);
              return new Acceptance(receipt(i, c, membership), true, event);
            });
    if (result.eventId() != null) dispatcher.dispatch(result.eventId());
    return result;
  }

  public InvitationDeclineResponse decline(Long actor, RecipientInvitationRequest request) {
    var candidate = candidate(request);
    return transaction.execute(
        tx -> {
          var locked = lock(actor, candidate, request.secret());
          var i = locked.invitation();
          if (i.getStatus() == InvitationStatus.DECLINED && actor.equals(i.getDecidedByUserId()))
            return new InvitationDeclineResponse(i.getId(), i.getStatus(), i.getDecidedAt());
          requirePending(i);
          i.setStatus(InvitationStatus.DECLINED);
          i.setDecidedByUserId(actor);
          i.setDecidedAt(clock.instant());
          i.setUpdatedAt(clock.instant());
          audit.record(
              actor,
              actor,
              locked.company().getId(),
              "INVITATION_DECLINED",
              null,
              i.getId(),
              null,
              null);
          return new InvitationDeclineResponse(i.getId(), i.getStatus(), i.getDecidedAt());
        });
  }

  private CompanyInvitationRepository.Candidate candidate(RecipientInvitationRequest request) {
    if (request == null || !validator.validateProperty(request, "secret").isEmpty())
      throw unavailable();
    return invitations
        .findCandidateBySecretHash(InvitationSecrets.hash(request.secret()))
        .orElseThrow(RecipientInvitationService::unavailable);
  }

  private Locked lock(Long actor, CompanyInvitationRepository.Candidate candidate, String secret) {
    var user = access.requireActiveUser(actor);
    var company =
        companies
            .findByIdForUpdate(candidate.getCompanyId())
            .orElseThrow(RecipientInvitationService::unavailable);
    var invitation =
        invitations
            .findByIdForUpdate(candidate.getId())
            .orElseThrow(RecipientInvitationService::unavailable);
    String email = user.getEmail().trim().toLowerCase(Locale.ROOT);
    if (!company.getId().equals(invitation.getCompanyId())
        || !InvitationSecrets.matches(secret, invitation.getSecretHash())
        || !email.equals(invitation.getRecipientEmailNormalized())) throw unavailable();
    return new Locked(company, invitation, user);
  }

  private void requirePending(CompanyInvitation i) {
    if (i.getStatus() != InvitationStatus.PENDING || !clock.instant().isBefore(i.getExpiresAt()))
      throw unavailable();
  }

  private static void requireEligible(Company c) {
    if (c.getVerificationStatus() != VerificationStatus.VERIFIED
        || c.isBanned()
        || c.getOwnerUserId() == null) throw unavailable();
  }

  private void requireConfirmation(RecipientInvitationRequest request) {
    if (!validator.validateProperty(request, "confirm", RecipientInvitationRequest.Acceptance.class).isEmpty())
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Explicit confirmation required");
  }

  private static InvitationAcceptanceResponse receipt(
      CompanyInvitation i, Company c, CompanyMembership m) {
    return new InvitationAcceptanceResponse(
        i.getId(), m.getId(), c.getId(), c.getName(), m.getRole(), m.getStatus(), i.getDecidedAt());
  }

  public static ResponseStatusException unavailable() {
    return new ResponseStatusException(
        HttpStatus.NOT_FOUND, "40410 INVITATION_UNAVAILABLE: Invitation unavailable");
  }

  private record Locked(Company company, CompanyInvitation invitation, User user) {}

  public record Acceptance(InvitationAcceptanceResponse receipt, boolean created, UUID eventId) {}
}
