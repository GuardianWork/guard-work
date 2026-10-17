package com.guardwork.backend.db.migration;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class MigrationV7Test {
    @Autowired JdbcTemplate database;

    @Test
    void databaseEnforcesPendingEmailAndSecretUniquenessWhileRetainingTerminalHistory() {
        long actor=user(),company=company();
        String email="invitations@example.vn";
        String hash=uniqueHash();
        long invitation=invite(company,actor,email,hash);
        assertThatThrownBy(() -> invite(company,actor,email,uniqueHash())).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_company_invitation_one_pending_per_email");
        assertThatThrownBy(() -> invite(company,actor,"other@example.vn",hash)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("secret_hash");
        database.update("UPDATE company_invitations SET status='REVOKED',decided_at=now() WHERE id=?",invitation);
        invite(company,actor,email,uniqueHash());
    }

    @Test
    void nullAuditSubjectsAreLimitedToMailboxInvitationFactsAndReferencesAreEnforced() {
        long actor=user(),company=company();
        long invitation=invite(company,actor,"audit@example.vn",uniqueHash());
        database.update("INSERT INTO company_membership_audit_events(actor_user_id,company_id,event_type,invitation_id) VALUES (?,?,'INVITATION_ISSUED',?)",actor,company,invitation);
        assertThatThrownBy(() -> database.update("INSERT INTO company_membership_audit_events(actor_user_id,company_id,event_type) VALUES (?,?,'COMPANY_CREATED')",actor,company))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_membership_audit_subject");
        assertThatThrownBy(() -> database.update("INSERT INTO company_membership_audit_events(actor_user_id,company_id,event_type,invitation_id) VALUES (?,?,'INVITATION_ISSUED',?)",actor,company,Long.MAX_VALUE))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_membership_audit_invitation");
    }
    String uniqueHash() {
        return UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
    }
    long invite(long company,long actor,String email,String hash) {
        return database.queryForObject("INSERT INTO company_invitations(company_id,recipient_email_normalized,role,invited_by_user_id,status,secret_hash,issued_at,expires_at,created_at,updated_at,delivery_status) VALUES (?,?,'RECRUITER',?,'PENDING',?,now(),now()+interval '7 days',now(),now(),'UNKNOWN') RETURNING id",Long.class,company,email,actor,hash);
    }
    long user() {
        String identity="db_"+UUID.randomUUID().toString().replace("-","");
        return database.queryForObject("INSERT INTO users(first_name,last_name,username,email,password) VALUES ('Ánh','Nguyễn',?,?,'fixture') RETURNING id",Long.class,identity,identity+"@example.vn");
    }
    long company() {
        return database.queryForObject("INSERT INTO companies(name,tax_code,registration_certificate_url) VALUES ('Công ty Ánh',?,'https://storage.guardwork.vn/license.pdf') RETURNING id",Long.class,UUID.randomUUID().toString());
    }
}
