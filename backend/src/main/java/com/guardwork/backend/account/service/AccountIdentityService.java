package com.guardwork.backend.account.service;

import com.guardwork.backend.audit.service.AuditService;
import com.guardwork.backend.auth.Portal;
import com.guardwork.backend.user.AccountPhone;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Called with the User locked by the owning business transaction. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class AccountIdentityService {
    private final UserRepository users;
    private final AuditService audit;
    public AccountIdentityService(UserRepository users, AuditService audit) { this.users=users;this.audit=audit; }

    public void update(User user,String firstName,String lastName,String phone) {
        requireEligible(user);
        if(firstName==null || firstName.isBlank() || firstName.trim().length()>100
                || lastName==null || lastName.isBlank() || lastName.trim().length()>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"40001 INVALID_INPUT: First and last name are required, maximum 100 characters each");
        String normalized;
        try { normalized=AccountPhone.normalize(phone); }
        catch(IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"40001 INVALID_INPUT: Invalid phone number"); }
        user.setFirstName(firstName.trim());user.setLastName(lastName.trim());user.setPhoneNumber(normalized);
        try { users.saveAndFlush(user); }
        catch(DataIntegrityViolationException conflict) {
            if(String.valueOf(conflict.getMostSpecificCause().getMessage()).contains("uq_users_phone_number"))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"40930 IDENTIFIER_UNAVAILABLE: Contact information is unavailable");
            throw conflict;
        }
        audit.recordLog(user.getId(),"ACCOUNT_DETAILS_UPDATED","USER",user.getId().toString(),null,
                "{\"fields\":[\"firstName\",\"lastName\",\"phoneNumber\"]}",null,null,null);
    }

    public static void requireEligible(User user) {
        Portal.PUBLIC.requireRole(user.getRole());
        if(!user.allowsNormalAccess()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"40304 ACCOUNT_SUSPENDED: Account unavailable");
    }
}
