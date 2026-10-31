package com.guardwork.backend.membership.service;

import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.membership.model.*;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.user.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Authorization stays in the business transaction, with Company -> user -> membership locks. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CompanyAccessService {
  private final com.guardwork.backend.staff.service.AccountEnforcementService enforcement;
  private final CompanyRepository companies;
  private final CompanyMembershipRepository memberships;

  public CompanyAccessService(
      CompanyRepository companies, CompanyMembershipRepository memberships,
      com.guardwork.backend.staff.service.AccountEnforcementService enforcement) {
    this.enforcement = enforcement;
    this.companies = companies;
    this.memberships = memberships;
  }

  public User requireActiveUser(Long actorId) {
    User actor = enforcement.lockUser(actorId);
    if (!actor.allowsNormalAccess()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "40304 ACCOUNT_SUSPENDED: Account is suspended");
    }
    return actor;
  }

  public Access findCurrentCompanyAccess(Long actorId) {
    return findCurrentCompanyAccess(actorId, false);
  }

  /** Only Company status and retained-evidence reads may use this suspension exception. */
  public Access findCurrentCompanyAccessForStatus(Long actorId) {
    return findCurrentCompanyAccess(actorId, true);
  }

  public Access requireOwnerForStatus(Long actorId) {
    return requireOwner(findCurrentCompanyAccessForStatus(actorId), actorId);
  }

  public Company requireOwnerOfForStatus(Long actorId, Long companyId) {
    var access = findCurrentCompanyAccessForStatus(actorId);
    if (access == null || !access.company().getId().equals(companyId)
        || access.membership().getRole() != CompanyRole.OWNER
        || !actorId.equals(access.company().getOwnerUserId())) throw notFound();
    return access.company();
  }

  private Access findCurrentCompanyAccess(Long actorId, boolean statusRead) {
    // Do not cache the membership entity before waiting on Company: its role may change meanwhile.
    var candidate = memberships.findActiveCompanyId(actorId);
    if (candidate.isEmpty()) return null;
    Company company =
        companies.findByIdForUpdate(candidate.get()).orElseThrow(CompanyAccessService::notFound);
    enforcement.settleCompany(company);
    User actor = requireActiveUser(actorId);
    if (!statusRead) {
      com.guardwork.backend.auth.service.AccountEmailVerificationService.requireRecruitingEmailEligible(actor);
    }
    CompanyMembership member =
        memberships.findActiveForUpdate(actorId).orElse(null);
    if (member == null) return null;
    if (!member.getCompanyId().equals(company.getId())
        || (company.isBanned() && (!statusRead || member.getRole() != CompanyRole.OWNER))
        || company.getOwnerUserId() == null) {
      throw notFound();
    }
    return new Access(company, member);
  }

  public Access requireOwner(Long actorId) {
    return requireOwner(findCurrentCompanyAccess(actorId), actorId);
  }

  private Access requireOwner(Access access, Long actorId) {
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
