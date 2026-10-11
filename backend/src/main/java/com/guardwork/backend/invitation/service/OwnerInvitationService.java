package com.guardwork.backend.invitation.service;

import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.company.model.*;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.invitation.dto.*;
import com.guardwork.backend.invitation.mail.CompanyInvitationMailer;
import com.guardwork.backend.invitation.model.*;
import com.guardwork.backend.invitation.repository.CompanyInvitationRepository;
import com.guardwork.backend.membership.service.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OwnerInvitationService {
  private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private final CompanyAccessService access;
  private final CompanyInvitationRepository invitations;
  private final CompanyRepository companies;
  private final MembershipAuditService audit;
  private final CompanyInvitationMailer mailer;
  private final Clock clock;
  private final TransactionTemplate transaction;

  public OwnerInvitationService(
      CompanyAccessService access,
      CompanyInvitationRepository invitations,
      CompanyRepository companies,
      MembershipAuditService audit,
      CompanyInvitationMailer mailer,
      Clock clock,
      PlatformTransactionManager manager) {
    this.access = access;
    this.invitations = invitations;
    this.companies = companies;
    this.audit = audit;
    this.mailer = mailer;
    this.clock = clock;
    this.transaction = new TransactionTemplate(manager);
  }

  public OwnerInvitationResponse issue(Long actor, IssueInvitationRequest request) {
    String secret = InvitationSecrets.generate();
    Long id =
        transaction.execute(
            tx -> {
              Company company = eligibleOwner(actor);
              String email = normalize(request == null ? null : request.email());
              invitations
                  .findPendingForUpdate(company.getId(), email)
                  .ifPresent(
                      existing -> {
                        if (expire(existing)) invitations.saveAndFlush(existing);
                        else
                          throw new ResponseStatusException(
                              HttpStatus.CONFLICT,
                              "40911 PENDING_INVITATION_EXISTS: Pending invitation already exists");
                      });
              CompanyInvitation invitation = new CompanyInvitation();
              invitation.setCompanyId(company.getId());
              invitation.setRecipientEmailNormalized(email);
              invitation.setCreatedAt(clock.instant());
              rotate(invitation, actor, secret);
              invitations.saveAndFlush(invitation);
              audit.record(
                  actor,
                  null,
                  company.getId(),
                  "INVITATION_ISSUED",
                  null,
                  invitation.getId(),
                  null,
                  null);
              return invitation.getId();
            });
    return deliver(id, secret);
  }

  public OwnerInvitationResponse resend(Long actor, Long id) {
    String secret = InvitationSecrets.generate();
    Boolean rotated =
        transaction.execute(
            tx -> {
              Company company = eligibleOwner(actor);
              CompanyInvitation invitation = owned(company, id);
              if (expire(invitation))
                return false; // Commit expiry before returning the unavailable result.
              requirePending(invitation);
              rotate(invitation, actor, secret);
              audit.record(actor, null, company.getId(), "INVITATION_RESENT", null, id, null, null);
              return true;
            });
    if (!Boolean.TRUE.equals(rotated)) throw unavailable();
    return deliver(id, secret);
  }

  public OwnerInvitationResponse revoke(Long actor, Long id) {
    OwnerInvitationResponse response =
        transaction.execute(
            tx -> {
              Company company = access.requireOwner(actor).company();
              CompanyInvitation invitation = owned(company, id);
              if (expire(invitation)) return null;
              requirePending(invitation);
              invitation.setStatus(InvitationStatus.REVOKED);
              invitation.setDecidedAt(clock.instant());
              invitation.setUpdatedAt(clock.instant());
              audit.record(
                  actor, null, company.getId(), "INVITATION_REVOKED", null, id, null, null);
              return OwnerInvitationResponse.from(invitation, company);
            });
    if (response == null) throw unavailable();
    return response;
  }

  public PageResponse<OwnerInvitationResponse> list(Long actor, String filter, int page, int size) {
    return transaction.execute(
        tx -> {
          Company company = access.requireOwner(actor).company();
          if (page < 0 || size < 1 || size > 100) throw badRequest();
          InvitationStatus status = null;
          if (filter != null) {
            try {
              status = InvitationStatus.valueOf(filter);
            } catch (IllegalArgumentException invalid) {
              throw badRequest();
            }
          }
          for (CompanyInvitation pending : invitations.findPendingForUpdate(company.getId()))
            expire(pending);
          var pagination = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
          var result =
              status == null
                  ? invitations.findByCompanyId(company.getId(), pagination)
                  : invitations.findByCompanyIdAndStatus(company.getId(), status, pagination);
          return PageResponse.of(
              result.getContent().stream()
                  .map(i -> OwnerInvitationResponse.from(i, company))
                  .toList(),
              page,
              size,
              result.getTotalElements());
        });
  }

  private Company eligibleOwner(Long actor) {
    Company company = access.requireOwner(actor).company();
    if (company.getVerificationStatus() != VerificationStatus.VERIFIED || company.isBanned())
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "40913 COMPANY_NOT_INVITATION_ELIGIBLE: Company is not eligible for invitations");
    return company;
  }

  private CompanyInvitation owned(Company company, Long id) {
    CompanyInvitation invitation =
        invitations.findByIdForUpdate(id).orElseThrow(OwnerInvitationService::unavailable);
    if (!company.getId().equals(invitation.getCompanyId())) throw unavailable();
    return invitation;
  }

  private void rotate(CompanyInvitation invitation, Long actor, String secret) {
    Instant now = clock.instant();
    invitation.setSecretHash(InvitationSecrets.hash(secret));
    invitation.setInvitedByUserId(actor);
    invitation.setIssuedAt(now);
    invitation.setExpiresAt(now.atZone(VIETNAM).plusDays(7).toInstant());
    invitation.setUpdatedAt(now);
    invitation.setDeliveryStatus(DeliveryStatus.UNKNOWN);
    invitation.setLastDeliveryAttemptAt(null);
  }

  private boolean expire(CompanyInvitation invitation) {
    if (invitation.getStatus() == InvitationStatus.PENDING
        && !clock.instant().isBefore(invitation.getExpiresAt())) {
      invitation.setStatus(InvitationStatus.EXPIRED);
      invitation.setUpdatedAt(clock.instant());
      invitation.setDecidedAt(clock.instant());
      return true;
    }
    return false;
  }

  private static void requirePending(CompanyInvitation invitation) {
    if (invitation.getStatus() != InvitationStatus.PENDING) throw unavailable();
  }

  /** Deliberately called only after the business transaction has committed. */
  private OwnerInvitationResponse deliver(Long id, String secret) {
    CompanyInvitation snapshot = invitations.findById(id).orElseThrow();
    Company company = companies.findById(snapshot.getCompanyId()).orElseThrow();
    DeliveryStatus result;
    try {
      result =
          Objects.requireNonNull(
              mailer.send(
                  snapshot.getRecipientEmailNormalized(),
                  company.getName(),
                  snapshot.getExpiresAt(),
                  secret));
    } catch (RuntimeException uncertain) {
      result = DeliveryStatus.UNKNOWN;
    }
    DeliveryStatus outcome = result;
    return transaction.execute(
        tx -> {
          CompanyInvitation current = invitations.findByIdForUpdate(id).orElseThrow();
          // A slow previous delivery cannot overwrite a resend, decision or revocation.
          if (current.getStatus() == InvitationStatus.PENDING
              && InvitationSecrets.matches(secret, current.getSecretHash())) {
            current.setDeliveryStatus(outcome);
            current.setLastDeliveryAttemptAt(clock.instant());
            current.setUpdatedAt(clock.instant());
          }
          return OwnerInvitationResponse.from(current, company);
        });
  }

  private static String normalize(String value) {
    if (value == null) throw badRequest();
    String email = value.trim().toLowerCase(Locale.ROOT);
    if (email.length() > 255 || !EMAIL.matcher(email).matches()) throw badRequest();
    return email;
  }

  private static ResponseStatusException badRequest() {
    return new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Invalid invitation request");
  }

  public static ResponseStatusException unavailable() {
    return new ResponseStatusException(
        HttpStatus.NOT_FOUND, "40410 INVITATION_UNAVAILABLE: Invitation unavailable");
  }
}
