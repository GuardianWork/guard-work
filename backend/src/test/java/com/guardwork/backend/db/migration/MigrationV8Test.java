package com.guardwork.backend.db.migration;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Approved PostgreSQL persistence boundary: source-event deduplication and scoped payload constraints. */
@SpringBootTest
@ActiveProfiles("test")
class MigrationV8Test {
    @Autowired JdbcTemplate database;

    @Test void sourceEventUniquenessPreventsDuplicateInboxItemsAndScopeRejectsUnapprovedEvents() {
        long recipient=user(), company=company(); UUID event=UUID.randomUUID();
        database.update("INSERT INTO notification_outbox(event_id,recipient_user_id,event_type,company_id,company_name,created_at,next_attempt_at) VALUES (?,?,'MEMBERSHIP_REMOVED',?,'Công ty Ánh',now(),now())",event,recipient,company);
        String insertion="INSERT INTO user_notifications(source_event_id,recipient_user_id,event_type,company_id,company_name,created_at) VALUES (?,?,'MEMBERSHIP_REMOVED',?,'Công ty Ánh',now())";
        database.update(insertion,event,recipient,company);
        assertThatThrownBy(() -> database.update(insertion,event,recipient,company)).isInstanceOf(DataAccessException.class).hasMessageContaining("source_event_id");
        assertThatThrownBy(() -> database.update("INSERT INTO notification_outbox(event_id,recipient_user_id,event_type,company_id,company_name,created_at,next_attempt_at) VALUES (?,?,'UNAPPROVED_EVENT',?,'Công ty Ánh',now(),now())",UUID.randomUUID(),recipient,company))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("event_type");
    }
    @Test void acceptedInvitationMustRetainItsOriginalMembershipReceipt() {
        long recipient=user(), company=company();
        assertThatThrownBy(() -> database.update("INSERT INTO company_invitations(company_id,recipient_email_normalized,role,invited_by_user_id,status,secret_hash,issued_at,expires_at,accepted_by_user_id,decided_at,created_at,updated_at,delivery_status) VALUES (?,'receipt@example.vn','RECRUITER',?,'ACCEPTED',?,now(),now()+interval '7 days',?,now(),now(),now(),'UNKNOWN')",company,recipient,"e".repeat(64),recipient))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_invitation_accepted_receipt");
    }
    long user() {
        String identity="db_"+UUID.randomUUID().toString().replace("-","");
        return database.queryForObject("INSERT INTO users(first_name,last_name,username,email,password) VALUES ('Ánh','Nguyễn',?,?,'fixture') RETURNING id",Long.class,identity,identity+"@example.vn");
    }
    long company() {
        return database.queryForObject("INSERT INTO companies(name,tax_code,registration_certificate_url) VALUES ('Công ty Ánh',?,'https://storage.guardwork.vn/license.pdf') RETURNING id",Long.class,UUID.randomUUID().toString());
    }
}
