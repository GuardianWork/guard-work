package com.guardwork.backend.membership.service;

import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.membership.dto.*;
import com.guardwork.backend.membership.model.*;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.notification.service.*;
import com.guardwork.backend.user.repository.UserRepository;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MembershipManagementService {
  private final CompanyAccessService access;
  private final CompanyMembershipRepository memberships;
  private final UserRepository users;
  private final MembershipAuditService audit;
  private final NotificationIntentService notifications;
  private final NotificationDispatcher dispatcher;
  private final Clock clock;
  private final TransactionTemplate transaction;

  public MembershipManagementService(
      CompanyAccessService access,
      CompanyMembershipRepository memberships,
      UserRepository users,
      MembershipAuditService audit,
      NotificationIntentService notifications,
      NotificationDispatcher dispatcher,
      Clock clock,
      PlatformTransactionManager manager) {
    this.access = access;
    this.memberships = memberships;
    this.users = users;
    this.audit = audit;
    this.notifications = notifications;
    this.dispatcher = dispatcher;
    this.clock = clock;
    transaction = new TransactionTemplate(manager);
  }

  public PageResponse<MemberResponse> list(Long actor, int page, int size) {
    return transaction.execute(
        tx -> {
          var company = access.requireOwner(actor).company();
          if (page < 0 || size < 1 || size > 100)
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "40000 BAD_REQUEST: Invalid membership page");
          var result =
              memberships.findByCompanyIdAndStatus(
                  company.getId(),
                  MembershipStatus.ACTIVE,
                  PageRequest.of(page, size, Sort.by("id")));
          var identities =
              users
                  .findAllById(
                      result.getContent().stream().map(CompanyMembership::getUserId).toList())
                  .stream()
                  .collect(
                      Collectors.toMap(
                          com.guardwork.backend.user.model.User::getId, Function.identity()));
          return PageResponse.of(
              result.getContent().stream()
                  .map(m -> MemberResponse.from(m, identities.get(m.getUserId())))
                  .toList(),
              page,
              size,
              result.getTotalElements());
        });
  }

  public MembershipEndResponse remove(Long actor, Long membershipId) {
    var result =
        transaction.execute(
            tx -> {
              var company = access.requireOwner(actor).company();
              // Lock only the scoped target membership after Company. Never acquire a target User
              // lock here.
              var target =
                  memberships
                      .findByIdAndCompanyForUpdate(membershipId, company.getId())
                      .orElseThrow(CompanyAccessService::notFound);
              if (target.getStatus() != MembershipStatus.ACTIVE)
                throw CompanyAccessService.notFound();
              requireRecruiter(target);
              end(target, actor, MembershipStatus.REMOVED);
              audit.record(
                  actor,
                  target.getUserId(),
                  company.getId(),
                  "MEMBERSHIP_REMOVED",
                  target.getId(),
                  null,
                  null,
                  null);
              UUID event =
                  notifications.record(
                      target.getUserId(), "MEMBERSHIP_REMOVED", company, target.getEndedAt());
              return new Removal(MembershipEndResponse.from(target), event);
            });
    dispatcher.dispatch(result.eventId());
    return result.response();
  }

  /** End the actor's current active Recruiter period. */
  public MembershipEndResponse leave(Long actor) {
    return transaction.execute(
        tx -> {
          var current = access.findCurrentCompanyAccess(actor);
          if (current == null)
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "40302 NOT_COMPANY_MEMBER: Active Company membership required");
          var target = current.membership();
          requireRecruiter(target);
          end(target, actor, MembershipStatus.LEFT);
          audit.record(
              actor,
              actor,
              current.company().getId(),
              "MEMBERSHIP_LEFT",
              target.getId(),
              null,
              null,
              null);
          return MembershipEndResponse.from(target);
        });
  }

  private void end(CompanyMembership target, Long actor, MembershipStatus status) {
    target.setStatus(status);
    target.setEndedAt(clock.instant());
    target.setEndedByUserId(actor);
    memberships.saveAndFlush(target);
  }

  private static void requireRecruiter(CompanyMembership target) {
    if (target.getRole() == CompanyRole.OWNER)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "40912 OWNER_MEMBERSHIP_PROTECTED: Owner membership cannot end");
    if (target.getRole() != CompanyRole.RECRUITER)
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "40303 INSUFFICIENT_COMPANY_ROLE: Recruiter role required");
  }

  private record Removal(MembershipEndResponse response, UUID eventId) {}
}
