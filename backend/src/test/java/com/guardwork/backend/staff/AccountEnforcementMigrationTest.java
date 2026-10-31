package com.guardwork.backend.staff;

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
class AccountEnforcementMigrationTest {
  @Autowired DataSource source;
  @Test void upgradePreservesLegacyStatesAndConstrainsManagedBans() {
    String schema="account_upgrade_"+UUID.randomUUID().toString().replace("-","");
    var db=new JdbcTemplate(source);
    try {
      Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("2026.10.30.85").load().migrate();
      long user=db.queryForObject("INSERT INTO "+schema+".users(first_name,last_name,username,email,password,status) VALUES ('An','Nguyen','legacy','legacy@test.invalid','hash','SUSPENDED') RETURNING id",Long.class);
      var upgrade=Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load();
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);
      assertThat(db.queryForObject("SELECT status FROM "+schema+".users WHERE id=?",String.class,user)).isEqualTo("SUSPENDED");
      assertThat(db.queryForObject("SELECT session_version FROM "+schema+".users WHERE id=?",Long.class,user)).isZero();
      db.update("UPDATE "+schema+".users SET status='BANNED' WHERE id=?",user);
      String insert="INSERT INTO "+schema+".account_bans(user_id,reason,started_at,created_by) VALUES (?,'Violation',now(),?)";
      db.update(insert,user,user);
      assertThatThrownBy(() -> db.update(insert,user,user)).isInstanceOf(DataIntegrityViolationException.class);
      assertThatThrownBy(() -> db.update("INSERT INTO "+schema+".account_bans(reason,started_at,created_by) VALUES ('Violation',now(),?)",user)).isInstanceOf(DataIntegrityViolationException.class);
      upgrade.validate();
    } finally { db.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE"); }
  }
}
