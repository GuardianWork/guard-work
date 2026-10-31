package com.guardwork.backend.auth;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AccountControlMigrationTest {
    @Autowired DataSource source;
    @Test void upgradeKeepsCanonicalIdentityEnforcementAndConstrainsPendingRequests() {
        String schema="own_account_"+UUID.randomUUID().toString().replace("-","");
        var db=new JdbcTemplate(source);
        try {
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("2026.10.31.86").load().migrate();
            long user=db.queryForObject("INSERT INTO "+schema+".users(first_name,last_name,username,email,password,status) VALUES ('Account','Name','legacy','legacy@test.invalid','hash','BANNED') RETURNING id",Long.class);
            db.update("INSERT INTO "+schema+".candidate_profiles(user_id,full_name,is_public) VALUES (?,'Conflicting profile',false)",user);
            var upgrade=Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(db.queryForObject("SELECT first_name || ' ' || last_name FROM "+schema+".users WHERE id=?",String.class,user)).isEqualTo("Account Name");
            assertThat(db.queryForObject("SELECT status FROM "+schema+".users WHERE id=?",String.class,user)).isEqualTo("BANNED");
            assertThat(db.queryForObject("SELECT self_service_state FROM "+schema+".users WHERE id=?",String.class,user)).isEqualTo("ACTIVE");
            assertThat(db.queryForObject("SELECT is_public FROM "+schema+".candidate_profiles WHERE user_id=?",Boolean.class,user)).isFalse();
            assertThat(db.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='candidate_profiles' AND column_name='full_name'",Integer.class,schema)).isZero();
            assertThatThrownBy(() -> db.update("UPDATE "+schema+".users SET self_service_state='ERASED' WHERE id=?",user)).isInstanceOf(DataIntegrityViolationException.class);
            String insert="INSERT INTO "+schema+".account_deletion_requests(user_id,requested_at) VALUES (?,now())";
            db.update(insert,user);
            assertThatThrownBy(() -> db.update(insert,user)).isInstanceOf(DataIntegrityViolationException.class);
            db.update("UPDATE "+schema+".account_deletion_requests SET cancelled_at=now() WHERE user_id=?",user);
            db.update(insert,user);
            assertThat(db.queryForObject("SELECT count(*) FROM "+schema+".account_deletion_requests WHERE user_id=?",Integer.class,user)).isEqualTo(2);
            upgrade.validate();
        } finally {db.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
    }
}
