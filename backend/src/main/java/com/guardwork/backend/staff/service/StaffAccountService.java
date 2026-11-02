package com.guardwork.backend.staff.service;

import tools.jackson.databind.ObjectMapper;
import com.guardwork.backend.audit.service.AuditService;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.job.repository.JobPostingRepository;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.staff.model.AccountBan;
import com.guardwork.backend.staff.repository.AccountBanRepository;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class StaffAccountService {
  private final UserRepository users;
  private final CompanyRepository companies;
  private final AccountBanRepository bans;
  private final JobPostingRepository jobs;
  private final CompanyMembershipRepository memberships;
  private final AccountEnforcementService enforcement;
  private final AuditService audit;
  private final ObjectMapper json;
  private final Clock clock;

  public StaffAccountService(UserRepository users, CompanyRepository companies,
      AccountBanRepository bans, JobPostingRepository jobs, CompanyMembershipRepository memberships,
      AccountEnforcementService enforcement, AuditService audit, ObjectMapper json, Clock clock) {
    this.users=users; this.companies=companies; this.bans=bans; this.jobs=jobs;
    this.memberships=memberships; this.enforcement=enforcement; this.audit=audit; this.json=json; this.clock=clock;
  }

  @Transactional(readOnly=true)
  public PageResponse<Map<String,Object>> search(Long actor, boolean company, String query, int page, int size) {
    requireStaff(actor,false);
    String q=query==null ? "" : query.strip();
    if(q.length()>200 || page<0 || size<1 || size>100) throw invalid();
    var pageable=PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"id"));
    if(company) {
      var result=companies.findAll(searchSpec(q,"name","taxCode"),pageable);
      return PageResponse.of(result.stream().map(c -> companyDetail(c)).toList(),page,size,result.getTotalElements());
    }
    var result=users.findAll(searchSpec(q,"username","email","firstName","lastName"),pageable);
    return PageResponse.of(result.stream().map(u -> userDetail(u,actor)).toList(),page,size,result.getTotalElements());
  }

  private <T> Specification<T> searchSpec(String q,String...fields) {
    return (root,query,cb) -> {
      if(q.isEmpty()) return cb.conjunction();
      String pattern="%"+q.toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
      List<Predicate> terms=new ArrayList<>();
      for(String field:fields) terms.add(cb.like(cb.lower(root.get(field)),pattern,'\\'));
      try { long id=Long.parseLong(q); if(id>0) terms.add(cb.equal(root.get("id"),id)); }
      catch(NumberFormatException ignored) { /* A textual query is not an ID. */ }
      return cb.or(terms.toArray(Predicate[]::new));
    };
  }

  @Transactional(readOnly=true)
  public Map<String,Object> detail(Long actor,boolean company,Long id) {
    requireStaff(actor,false); validId(id);
    return company ? companyDetail(companies.findById(id).orElseThrow(StaffAccountService::missing))
        : userDetail(users.findById(id).orElseThrow(StaffAccountService::missing),actor);
  }

  public Map<String,Object> change(Long actor,boolean company,Long id,boolean unban,
      String reason,Instant expiresAt,String ip,String agent) {
    validId(id);
    reason=reason==null ? "" : reason.strip();
    if(reason.isEmpty() || reason.length()>2000 || (!unban && expiresAt!=null && !expiresAt.isAfter(clock.instant()))) throw invalid();
    User user=null; Company target=null;
    if(company) {
      target=companies.findByIdForUpdate(id).orElseThrow(StaffAccountService::missing);
      // Company first, then all participating users in a deterministic order.
      var ids=new TreeSet<Long>(memberships.activeUserIds(id)); ids.add(actor);
      for(Long userId:ids) users.findByIdForUpdate(userId).orElseThrow(StaffAccountService::missing);
      enforcement.settleCompany(target);
    } else {
      for(Long userId:new TreeSet<>(List.of(actor,id))) users.findByIdForUpdate(userId).orElseThrow(StaffAccountService::missing);
      user=users.findById(id).orElseThrow(StaffAccountService::missing);
      enforcement.settleUser(user);
    }
    requireStaff(actor,true);
    if(!company && actor.equals(id) && !unban) throw error(HttpStatus.FORBIDDEN,"40392 SELF_BAN");
    var current=company ? bans.findByCompanyIdAndEndedAtIsNull(id) : bans.findByUserIdAndEndedAtIsNull(id);
    boolean active=current.filter(b -> b.activeAt(clock.instant())).isPresent();
    if(unban ? !active : active) throw conflict();
    if(unban && (company ? !target.isBanned() : !"BANNED".equals(user.getStatus()))) throw conflict();
    Instant startedAt=clock.instant();
    if(!unban && expiresAt!=null && !expiresAt.isAfter(startedAt)) throw invalid();
    if(!unban && (company ? target.isBanned() : !"ACTIVE".equals(user.getStatus()))) throw conflict();
    var before=company ? companyDetail(target) : userDetail(user,actor);
    bans.flush();
    if(unban) {
      var ban=current.orElseThrow(StaffAccountService::conflict);
      ban.setEndedAt(clock.instant()); ban.setEndedBy(actor); ban.setEndReason(reason);
    } else {
      var ban=new AccountBan(); ban.setUserId(company ? null : id); ban.setCompanyId(company ? id : null);
      ban.setCreatedBy(actor); ban.setReason(reason); ban.setStartedAt(startedAt); ban.setExpiresAt(expiresAt);
      bans.save(ban);
    }
    if(company) {
      target.setBanned(!unban); target.setVersion(target.getVersion()+1); target.setUpdatedAt(clock.instant());
      enforcement.invalidateCompany(id);
      if(!unban) for(var job:jobs.lockPublished(id)) { job.setStaffSuspended(true); job.setUpdatedAt(clock.instant()); }
    } else {
      user.setStatus(unban ? "ACTIVE" : "BANNED"); enforcement.invalidate(user);
    }
    // Flush all mandatory work before constructing the response; any failure rolls back the audit too.
    bans.flush(); users.flush(); companies.flush(); jobs.flush();
    var after=company ? companyDetail(target) : userDetail(user,actor);
    requireStaff(actor,true);
    audit.recordLog(actor,unban ? "ACCOUNT_UNBANNED" : "ACCOUNT_BANNED",company ? "COMPANY" : "USER",
        id.toString(),encode(enforcementState(before)),encode(enforcementState(after)),reason,ip,agent);
    return after;
  }

  private void requireStaff(Long actor,boolean locked) {
    User user=(locked ? users.findByIdForUpdate(actor) : users.findById(actor))
        .orElseThrow(() -> error(HttpStatus.UNAUTHORIZED,"40100 UNAUTHORIZED"));
    if(!"ACTIVE".equals(user.getStatus()) || !"STAFF".equals(user.getRole()))
      throw error(HttpStatus.FORBIDDEN,"40300 FORBIDDEN: Active Platform Staff required");
  }

  private Map<String,Object> userDetail(User user,Long actor) {
    var ban=bans.findByUserIdAndEndedAtIsNull(user.getId()).orElse(null);
    boolean expired=ban!=null && !ban.activeAt(clock.instant());
    String status=expired && "BANNED".equals(user.getStatus()) ? "ACTIVE" : user.getStatus();
    var out=new LinkedHashMap<String,Object>();
    out.put("id",user.getId()); out.put("username",user.getUsername()); out.put("firstName",user.getFirstName());
    out.put("lastName",user.getLastName()); out.put("email",user.getEmail()); out.put("emailVerified",user.isEmailVerified());
    out.put("role",user.getRole()); out.put("status",status); out.put("createdAt",user.getCreatedAt()); out.put("updatedAt",user.getUpdatedAt());
    actions(out,ban,"ACTIVE".equals(status) && !actor.equals(user.getId()),"BANNED".equals(status)); return out;
  }

  private Map<String,Object> companyDetail(Company company) {
    var ban=bans.findByCompanyIdAndEndedAtIsNull(company.getId()).orElse(null);
    boolean banned=company.isBanned() && !(ban!=null && !ban.activeAt(clock.instant()));
    var out=new LinkedHashMap<String,Object>(); out.put("id",company.getId()); out.put("name",company.getName());
    out.put("taxCode",company.getTaxCode()); out.put("verificationStatus",company.getVerificationStatus()); out.put("isBanned",banned);
    out.put("createdAt",company.getCreatedAt()); out.put("updatedAt",company.getUpdatedAt());
    actions(out,ban,!banned,banned); return out;
  }

  private void actions(Map<String,Object> out,AccountBan ban,boolean canBan,boolean canUnban) {
    boolean active=ban!=null && ban.activeAt(clock.instant());
    out.put("activeBan",active ? new BanDetail(ban.getId(),ban.getReason(),ban.getStartedAt(),ban.getExpiresAt(),ban.getCreatedBy()) : null);
    out.put("canBan",canBan && !active); out.put("canUnban",active && canUnban);
  }
  public record BanDetail(Long id,String reason,Instant startedAt,Instant expiresAt,Long createdBy) {}
  private Map<String,Object> enforcementState(Map<String,Object> detail) {
    var state=new LinkedHashMap<String,Object>();
    for(String key:List.of("status","isBanned","activeBan")) if(detail.containsKey(key)) state.put(key,detail.get(key));
    return state;
  }
  private String encode(Object value) {
    try { return json.writeValueAsString(value); }
    catch(Exception failure) { throw new IllegalStateException("Cannot serialize enforcement audit",failure); }
  }
  private static void validId(Long id) { if(id==null || id<=0) throw invalid(); }
  private static ResponseStatusException error(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
  private static ResponseStatusException invalid() { return error(HttpStatus.BAD_REQUEST,"40092 INVALID_ACCOUNT_INPUT"); }
  private static ResponseStatusException missing() { return error(HttpStatus.NOT_FOUND,"40492 ACCOUNT_NOT_FOUND"); }
  private static ResponseStatusException conflict() { return error(HttpStatus.CONFLICT,"40992 ACCOUNT_STATE_CONFLICT"); }
}
