package com.guardwork.backend.db.migration;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class MigrationV5Test {
    @Autowired JdbcTemplate database;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void managedCompanyCannotCommitWithoutItsDeclaredActiveOwner() {
        long user = user();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx ->
                company(user))).isInstanceOf(DataAccessException.class).hasMessageContaining("declared active Owner");
    }

    @Test
    void unassignedCompanyCannotAcquireAnUndeclaredOwner() {
        long user = user();
        long company = company(null);
        assertThatThrownBy(() -> database.update(
                "INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',now())",
                company, user)).isInstanceOf(DataAccessException.class).hasMessageContaining("declared active Owner");
    }

    @Test
    void declaredOwnerMustMatchMembershipAndCannotBeReassigned() {
        long owner = user();
        long other = user();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            long company = company(owner);
            database.update("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',now())", company, other);
        })).isInstanceOf(DataAccessException.class).hasMessageContaining("declared active Owner");
        long company = new TransactionTemplate(transactions).execute(tx -> {
            long id = company(owner);
            database.update("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',now())", id, owner);
            return id;
        });
        assertThatThrownBy(() -> database.update("UPDATE companies SET owner_user_id=? WHERE id=?", other, company))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("cannot be transferred");
        assertThatThrownBy(() -> database.update("UPDATE companies SET owner_user_id=NULL WHERE id=?", company))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("cannot be transferred");
    }

    @Test
    void ownershipAuditRejectsUpdateDeleteAndTruncate() {
        long actor = user();
        long company = company(null);
        database.update("INSERT INTO company_membership_audit_events(actor_user_id,subject_user_id,company_id,event_type) VALUES (?,?,?,'TEST_REVIEW')", actor, actor, company);
        for (String command : new String[]{"UPDATE company_membership_audit_events SET reason='tampered' WHERE company_id=" + company,
                "DELETE FROM company_membership_audit_events WHERE company_id=" + company,
                "TRUNCATE company_membership_audit_events"}) {
            assertThatThrownBy(() -> database.execute(command)).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("append-only");
        }
    }

    long user() {
        String username = "db_" + UUID.randomUUID().toString().replace("-", "");
        return database.queryForObject("INSERT INTO users(first_name,last_name,username,email,password) VALUES ('Ánh','Nguyễn',?,?,'fixture') RETURNING id",
                Long.class, username, username + "@example.vn");
    }

    long company(Long owner) {
        return database.queryForObject("INSERT INTO companies(name,tax_code,registration_certificate_url,owner_user_id) VALUES ('Công ty Ánh Dương',?,'https://storage.guardwork.vn/license.pdf',?) RETURNING id",
                Long.class, UUID.randomUUID().toString(), owner);
    }
}
