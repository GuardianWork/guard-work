package com.guardwork.backend.staff.service;

import com.guardwork.backend.auth.repository.RefreshTokenRepository;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.staff.model.AccountBan;
import com.guardwork.backend.staff.repository.AccountBanRepository;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** All callers settle expiry while holding the target row lock. No cached enforcement state. */
@Service
@Transactional
public class AccountEnforcementService {
  private final jakarta.persistence.EntityManager entities;
  private final UserRepository users;
  private final CompanyRepository companies;
  private final AccountBanRepository bans;
  private final CompanyMembershipRepository memberships;
  private final RefreshTokenRepository refresh;
  private final Clock clock;

  public AccountEnforcementService(UserRepository users, CompanyRepository companies,
      AccountBanRepository bans, CompanyMembershipRepository memberships,
      RefreshTokenRepository refresh, Clock clock, jakarta.persistence.EntityManager entities) {
    this.entities=entities;
    this.users=users; this.companies=companies; this.bans=bans;
    this.memberships=memberships; this.refresh=refresh; this.clock=clock;
  }

  public User lockUser(Long id) {
    // Serialize token issuance/validation with Company bans before taking any member user lock.
    memberships.findActiveCompanyId(id).flatMap(companies::findByIdForUpdate).ifPresent(this::settleCompany);
    User user=users.findByIdForUpdate(id).orElseThrow(() -> error(HttpStatus.UNAUTHORIZED,"40100 UNAUTHORIZED"));
    entities.refresh(user);
    settleUser(user);
    return user;
  }

  public User authenticate(Long id, long version) {
    User user=lockUser(id);
    if (!user.allowsNormalAccess() || user.getSessionVersion()!=version)
      throw error(HttpStatus.UNAUTHORIZED,"40100 UNAUTHORIZED: Session invalidated");
    return user;
  }

  public void settleUser(User user) {
    bans.findByUserIdAndEndedAtIsNull(user.getId()).filter(b -> expired(b)).ifPresent(b -> {
      endExpired(b);
      if ("BANNED".equals(user.getStatus())) user.setStatus("ACTIVE");
    });
  }

  public void settleCompany(Company company) {
    bans.findByCompanyIdAndEndedAtIsNull(company.getId()).filter(b -> expired(b)).ifPresent(b -> {
      endExpired(b); invalidateCompany(company.getId()); company.setBanned(false); company.setVersion(company.getVersion()+1);
      company.setUpdatedAt(clock.instant());
    });
  }

  private boolean expired(AccountBan ban) {
    return ban.getExpiresAt()!=null && !ban.getExpiresAt().isAfter(clock.instant());
  }

  private void endExpired(AccountBan ban) {
    ban.setEndedAt(clock.instant()); ban.setEndReason("SYSTEM_EXPIRY");
  }

  public void invalidate(User user) {
    user.setSessionVersion(Math.addExact(user.getSessionVersion(),1));
    refresh.revokeAllForUser(user.getId(),clock.instant());
  }

  public void invalidateCompany(Long company) {
    for (Long id: memberships.activeUserIds(company)) {
      User user=users.findByIdForUpdate(id).orElseThrow(() -> error(HttpStatus.CONFLICT,"40992 ACCOUNT_STATE_CONFLICT"));
      settleUser(user);
      invalidate(user);
    }
  }

  public void expire(Long banId) {
    var hint=bans.findById(banId).orElse(null);
    if (hint==null) return;
    if (hint.getCompanyId()!=null) {
      companies.findByIdForUpdate(hint.getCompanyId()).ifPresent(this::settleCompany);
    } else {
      users.findByIdForUpdate(hint.getUserId()).ifPresent(this::settleUser);
    }
  }

  public static ResponseStatusException error(HttpStatus status,String message) {
    return new ResponseStatusException(status,message);
  }
}
