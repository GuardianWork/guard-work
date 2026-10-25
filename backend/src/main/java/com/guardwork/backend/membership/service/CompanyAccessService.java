package com.guardwork.backend.membership.service;

import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.membership.model.*;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authorization remains inside the business transaction, with actor -> Company -> membership locks.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CompanyAccessService {
  private final UserRepository users;
  private final CompanyRepository companies;
  private final CompanyMembershipRepository memberships;

  public CompanyAccessService(
      UserRepository users, CompanyRepository companies, CompanyMembershipRepository memberships) {
    this.users = users;
    this.companies = companies;
    this.memberships = memberships;
  }

  public User requireActiveUser(Long actorId) {
    User actor =
        users
            .findByIdForUpdate(actorId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "40100 UNAUTHORIZED: Authentication required"));
    if (!"ACTIVE".equals(actor.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "40304 ACCOUNT_SUSPENDED: Account is suspended");
    }
    return actor;
  }

  public Access findCurrentCompanyAccess(Long actorId) {
    requireActiveUser(actorId);
    // Do not cache the membership entity before waiting on Company: its role may change meanwhile.
    var candidate = memberships.findActiveCompanyId(actorId);
    if (candidate.isEmpty()) return null;
    Company company =
        companies.findByIdForUpdate(candidate.get()).orElseThrow(CompanyAccessService::notFound);
    CompanyMembership member =
        memberships.findActiveForUpdate(actorId).orElseThrow(CompanyAccessService::notFound);
    if (!member.getCompanyId().equals(company.getId())
        || company.isBanned()
        || company.getOwnerUserId() == null) {
      throw notFound();
    }
    return new Access(company, member);
  }

  public Access requireOwner(Long actorId) {
    Access access = findCurrentCompanyAccess(actorId);
    if (access == null) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "40302 NOT_COMPANY_MEMBER: Active Company membership required");
    }
    if (access.membership().getRole() != CompanyRole.OWNER
        || !actorId.equals(access.company().getOwnerUserId())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "40303 INSUFFICIENT_COMPANY_ROLE: Owner role required");
    }
    return access;
  }

  public Company requireOwnerOf(Long actorId, Long companyId) {
    Access access = findCurrentCompanyAccess(actorId);
    if (access == null
        || !access.company().getId().equals(companyId)
        || access.membership().getRole() != CompanyRole.OWNER
        || !actorId.equals(access.company().getOwnerUserId())) throw notFound();
    return access.company();
  }

  public static ResponseStatusException notFound() {
    return new ResponseStatusException(
        HttpStatus.NOT_FOUND, "40400 COMPANY_NOT_FOUND: Company not found");
  }

  public record Access(Company company, CompanyMembership membership) {}
}
