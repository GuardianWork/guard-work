package com.guardwork.backend.account.service;

import com.guardwork.backend.account.model.*;
import com.guardwork.backend.account.repository.*;
import com.guardwork.backend.audit.service.AuditService;
import com.guardwork.backend.auth.Portal;
import com.guardwork.backend.candidate.CandidateProfileRepository;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.onboarding.delivery.OnboardingDelivery;
import com.guardwork.backend.onboarding.service.OnboardingSecrets;
import com.guardwork.backend.staff.service.AccountEnforcementService;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountControlService {
    public enum Action {
        DEACTIVATE("ACTIVE","DEACTIVATED"), DELETE("ACTIVE","DELETION_PENDING"),
        REACTIVATE("DEACTIVATED","ACTIVE"), CANCEL_DELETION("DELETION_PENDING","ACTIVE");
        final String source,target;
        Action(String source,String target) {this.source=source;this.target=target;}
        boolean recovery() {return this==REACTIVATE || this==CANCEL_DELETION;}
    }
    public record AccountView(String firstName,String lastName,String fullName,String phoneNumber,
        String username,String email,boolean emailVerified,String selfServiceState,boolean closureEligible,List<String> closureBlockers) {}
    public record ActionResult(String selfServiceState) {}
    public record DeliveryResult(boolean deliveryAccepted,Instant nextRequestAt,Instant expiresAt) {}
    private record Issued(String email,Instant nextRequestAt,Instant expiresAt) {}
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AccountControlService.class);
    private static final SecureRandom RANDOM=new SecureRandom();
    private final UserRepository users;
    private final CompanyRepository companies;
    private final CompanyMembershipRepository members;
    private final CandidateProfileRepository profiles;
    private final AccountActionProofRepository proofs;
    private final AccountDeletionRequestRepository deletions;
    private final AccountEnforcementService enforcement;
    private final AccountIdentityService identity;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final OnboardingDelivery delivery;
    private final Clock clock;
    private final TransactionTemplate tx;

    public AccountControlService(UserRepository users,CompanyRepository companies,CompanyMembershipRepository members,
            CandidateProfileRepository profiles,AccountActionProofRepository proofs,AccountDeletionRequestRepository deletions,
            AccountEnforcementService enforcement,AccountIdentityService identity,PasswordEncoder passwords,
            AuditService audit,OnboardingDelivery delivery,Clock clock,PlatformTransactionManager transactions) {
        this.users=users;this.companies=companies;this.members=members;this.profiles=profiles;this.proofs=proofs;
        this.deletions=deletions;this.enforcement=enforcement;this.identity=identity;this.passwords=passwords;
        this.audit=audit;this.delivery=delivery;this.clock=clock;
        tx=new TransactionTemplate(transactions);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public AccountView view(Long id) {return tx.execute(s -> projection(normalUser(id)));}
    public AccountView update(Long id,String first,String last,String phone) {
        return tx.execute(s -> {User u=normalUser(id);identity.update(u,first,last,phone);return projection(u);});
    }
    private User normalUser(Long id) {
        User u=enforcement.lockUser(id);AccountIdentityService.requireEligible(u);return u;
    }
    private List<String> blockers(User u) {
        var result=new ArrayList<String>();
        if(!u.isEmailVerified()) result.add("EMAIL_VERIFICATION_REQUIRED");
        if(companies.existsByOwnerUserId(u.getId())) result.add("COMPANY_OWNERSHIP_EXISTS");
        if(members.findActiveCompanyId(u.getId()).isPresent()) result.add("ACTIVE_COMPANY_MEMBERSHIP_EXISTS");
        return result;
    }
    private AccountView projection(User u) {
        var reasons=blockers(u);
        return new AccountView(u.getFirstName(),u.getLastName(),u.fullName(),u.getPhoneNumber(),u.getUsername(),u.getEmail(),
            u.isEmailVerified(),u.getSelfServiceState(),reasons.isEmpty(),List.copyOf(reasons));
    }

    public DeliveryResult requestClosure(Long id,Action action,String password) {
        if(action.recovery()) throw invalidInput();
        return issue(() -> eligible(normalUser(id),action,password),action);
    }
    public ActionResult confirmClosure(Long id,Action action,String password,String secret,boolean acknowledged) {
        if(action.recovery()) throw invalidInput();
        confirm(() -> eligible(normalUser(id),action,password),action,secret,acknowledged);
        return new ActionResult(action.target);
    }
    public DeliveryResult requestRecovery(String identifier,String password,Action action) {
        if(!action.recovery()) throw invalidInput();
        return issue(() -> recoveryUser(identifier,password,action),action);
    }
    public ActionResult confirmRecovery(String identifier,String password,Action action,String secret,boolean acknowledged) {
        if(!action.recovery()) throw invalidInput();
        confirm(() -> recoveryUser(identifier,password,action),action,secret,acknowledged);
        return new ActionResult(action.target);
    }

    private User recoveryUser(String identifier,String password,Action action) {
        if(identifier==null || password==null) throw credentials();
        String value=identifier.trim();
        User hint=users.findByUsername(value).or(() -> users.findByEmail(value.toLowerCase(Locale.ROOT))).orElseThrow(AccountControlService::credentials);
        if(!passwords.matches(password,hint.getPassword())) throw credentials();
        User u=enforcement.lockUser(hint.getId());
        if(!passwords.matches(password,u.getPassword())) throw credentials();
        Portal.PUBLIC.requireRole(u.getRole());
        if(!"ACTIVE".equals(u.getStatus())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"40304 ACCOUNT_SUSPENDED");
        return eligible(u,action,password);
    }
    private User eligible(User u,Action action,String password) {
        if(!action.source.equals(u.getSelfServiceState())) throw conflict();
        if(!u.isEmailVerified()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"40335 EMAIL_VERIFICATION_REQUIRED");
        if(password==null || !passwords.matches(password,u.getPassword()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"40001 INVALID_INPUT: Current password does not match");
        if(!action.recovery()) {
            if(companies.existsByOwnerUserId(u.getId())) throw new ResponseStatusException(HttpStatus.CONFLICT,"40994 COMPANY_OWNERSHIP_EXISTS");
            if(members.findActiveCompanyId(u.getId()).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT,"40910 ACTIVE_COMPANY_MEMBERSHIP_EXISTS");
        }
        if(action==Action.CANCEL_DELETION && deletions.findByUserIdAndCancelledAtIsNull(u.getId()).isEmpty()) throw conflict();
        return u;
    }

    private DeliveryResult issue(java.util.function.Supplier<User> authorized,Action action) {
        String code=String.format(Locale.ROOT,"%06d",RANDOM.nextInt(1_000_000));
        Issued issued=tx.execute(s -> {
            User u=authorized.get();Instant now=clock.instant();
            AccountActionProof p=proofs.findByUserIdAndPurpose(u.getId(),action.name()).orElseGet(AccountActionProof::new);
            if(p.getIssuedAt()!=null && now.isBefore(p.getIssuedAt().plusSeconds(60)))
                throw new Cooldown(Math.max(1,(Duration.between(now,p.getIssuedAt().plusSeconds(60)).toMillis()+999)/1000));
            p.setUserId(u.getId());p.setPurpose(action.name());p.setEmail(normalized(u.getEmail()));
            p.setSecretHash(OnboardingSecrets.hash(code));p.setIssuedAt(now);p.setExpiresAt(now.plusSeconds(600));p.setFailedAttempts(0);
            proofs.saveAndFlush(p);
            return new Issued(u.getEmail(),now.plusSeconds(60),p.getExpiresAt());
        });
        boolean accepted;
        try {accepted=delivery.accountActionEmail(issued.email(),code,action.name());} catch(RuntimeException failure) {
            com.guardwork.backend.logging.DiagnosticLog.error(log,
                com.guardwork.backend.logging.DiagnosticEvent.UNEXPECTED_FAILURE, failure);
            accepted=false;
        }
        return new DeliveryResult(accepted,issued.nextRequestAt(),issued.expiresAt());
    }

    private void confirm(java.util.function.Supplier<User> authorized,Action action,String code,boolean acknowledged) {
        if(!acknowledged) throw invalidInput();
        // Return invalid-proof as data so attempt accounting commits before returning HTTP 400.
        boolean success=Boolean.TRUE.equals(tx.execute(s -> {
            User u=authorized.get();Instant now=clock.instant();
            AccountActionProof p=proofs.findByUserIdAndPurpose(u.getId(),action.name()).orElse(null);
            if(p==null || p.getSecretHash()==null || p.getFailedAttempts()>=5 || !now.isBefore(p.getExpiresAt())
                    || !normalized(u.getEmail()).equals(p.getEmail())) return false;
            if(code==null || !code.matches("[0-9]{6}") || !OnboardingSecrets.matches(code,p.getSecretHash())) {
                p.setFailedAttempts(p.getFailedAttempts()+1);if(p.getFailedAttempts()==5) p.setSecretHash(null);
                proofs.saveAndFlush(p);return false;
            }
            transition(u,action,now);
            // A proof issued before any completed lifecycle transition must never work in a later cycle.
            for(var prior:proofs.findByUserId(u.getId())) prior.setSecretHash(null);
            return true;
        }));
        if(!success) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"40094 INVALID_ACCOUNT_ACTION_PROOF");
    }

    private void transition(User u,Action action,Instant now) {
        String before=u.getSelfServiceState();
        if(!action.recovery()) {
            profiles.findByUserId(u.getId()).ifPresent(p -> {u.setPriorCandidateVisibility(p.isPublic());p.setPublic(false);});
            enforcement.invalidate(u);
            if(action==Action.DELETE) {
                AccountDeletionRequest r=new AccountDeletionRequest();r.setUserId(u.getId());r.setRequestedAt(now);deletions.save(r);
            }
        } else {
            if(action==Action.CANCEL_DELETION) deletions.findByUserIdAndCancelledAtIsNull(u.getId()).orElseThrow(AccountControlService::conflict).setCancelledAt(now);
            profiles.findByUserId(u.getId()).ifPresent(p -> {if(u.getPriorCandidateVisibility()!=null) p.setPublic(u.getPriorCandidateVisibility());});
            u.setPriorCandidateVisibility(null);
        }
        u.setSelfServiceState(action.target);
        audit.recordLog(u.getId(),"ACCOUNT_"+action.name(),"USER",u.getId().toString(),
            "{\"selfServiceState\":\""+before+"\"}","{\"selfServiceState\":\""+action.target+"\"}",null,null,null);
    }
    private static String normalized(String email) {return email.trim().toLowerCase(Locale.ROOT);}
    private static ResponseStatusException conflict() {return new ResponseStatusException(HttpStatus.CONFLICT,"40995 ACCOUNT_LIFECYCLE_CONFLICT");}
    private static ResponseStatusException credentials() {return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"40101 INVALID_CREDENTIALS");}
    private static ResponseStatusException invalidInput() {return new ResponseStatusException(HttpStatus.BAD_REQUEST,"40001 INVALID_INPUT");}
    private static class Cooldown extends ResponseStatusException {
        private final HttpHeaders headers=new HttpHeaders();
        Cooldown(long seconds) {super(HttpStatus.TOO_MANY_REQUESTS,"42994 ACCOUNT_ACTION_COOLDOWN");headers.set("Retry-After",Long.toString(seconds));}
        @Override public HttpHeaders getHeaders() {return headers;}
    }
}
