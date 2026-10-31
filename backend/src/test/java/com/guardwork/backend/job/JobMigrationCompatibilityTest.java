package com.guardwork.backend.job;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs baseline and upgrade migrations in an isolated schema of the approved test database. */
@SpringBootTest(properties = "company.membership.notifications.worker-enabled=false")
@ActiveProfiles("test")
class JobMigrationCompatibilityTest {
  @Autowired DataSource dataSource;

  @Test void suspensionMigrationPreservesPublishedContentAndImmutableHistory() throws Exception {
    String schema = "staff_job_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var admin = new JdbcTemplate(dataSource);
    try {
      Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
          .target("2026.10.30.85").load().migrate();
      try (var connection = dataSource.getConnection()) {
        String originalSchema = connection.getSchema();
        try {
          connection.setSchema(schema);
          var database = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
          long user = database.queryForObject("INSERT INTO users(first_name,last_name,username,email,password,role) "
              + "VALUES ('Staff','Reviewer','reviewer','reviewer@example.vn','fixture','STAFF') RETURNING id", Long.class);
          long company = database.queryForObject("INSERT INTO companies(name,tax_code,registration_certificate_url) "
              + "VALUES ('Retained Company','retained-tax','https://example.vn/license') RETURNING id", Long.class);
          long job = database.queryForObject("INSERT INTO job_postings(company_id,creator_user_id,status,owner_responsible,created_at,updated_at) "
              + "VALUES (?,?,'DRAFT',true,now(),now()) RETURNING id", Long.class, company, user);
          long revision = database.queryForObject("INSERT INTO job_posting_revisions(job_id,revision_number,content,state,creator_user_id,created_at,submitter_user_id,submitted_at,reviewer_user_id,decided_at) "
              + "VALUES (?,1,'{\"deadline\":\"2099-12-01T00:00:00Z\",\"title\":\"Retained content\"}'::jsonb,'APPROVED',?,now(),?,now(),?,now()) RETURNING id", Long.class, job, user, user, user);
          database.update("UPDATE job_postings SET status='PUBLISHED',published_revision_id=?,approved_deadline='2099-12-01T00:00:00Z' WHERE id=?",revision,job);
          database.update("INSERT INTO job_audit_events(company_id,job_id,actor_user_id,event_type,created_at,facts) VALUES (?,?,?,'JOB_CREATED',now(),'{\"ownerResponsible\":true}')", company, job, user);
          String original = database.queryForObject("SELECT content::text FROM job_posting_revisions WHERE id=?",String.class,revision);
          var upgrade = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
          assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
          upgrade.validate();
          assertThat(database.queryForObject("SELECT status FROM job_postings WHERE id=?",String.class,job)).isEqualTo("PUBLISHED");
          database.update("UPDATE job_postings SET status='SUSPENDED' WHERE id=?",job);
          assertThat(database.queryForObject("SELECT content::text FROM job_posting_revisions WHERE id=?",String.class,revision)).isEqualTo(original);
          assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=?",Long.class,job)).isEqualTo(1);
          for (String sql : java.util.List.of("UPDATE job_postings SET status='UNKNOWN' WHERE id="+job,
              "UPDATE job_audit_events SET event_type='JOB_SUSPENDED' WHERE job_id="+job,
              "DELETE FROM job_audit_events WHERE job_id="+job,
              "UPDATE job_posting_revisions SET content='{}'::jsonb WHERE id="+revision)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(()->database.update(sql))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
          }
        } finally {
          connection.setSchema(originalSchema);
        }
      }
    } finally {
      admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test void codeMigrationInvalidatesOldAccountLinksWithoutChangingVerifiedFlags() {
    String schema = "email_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var database = new JdbcTemplate(dataSource);
    try {
      Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
          .target("2026.10.28.84").load().migrate();
      long user = database.queryForObject("INSERT INTO " + schema
          + ".users(first_name,last_name,username,email,password,role,email_verified) "
          + "VALUES ('Email','User','email_user','email@example.vn','fixture','USER',false) RETURNING id", Long.class);
      database.update("INSERT INTO " + schema
          + ".user_email_verification(user_id,email,secret_hash,issued_at,expires_at) "
          + "VALUES (?,'email@example.vn',repeat('a',64),now(),now()+interval '24 hours')", user);
      var upgrade = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);
      assertThat(database.queryForObject("SELECT secret_hash IS NULL AND consumed_at IS NOT NULL "
          + "AND failed_attempts=0 FROM " + schema + ".user_email_verification WHERE user_id=?", Boolean.class, user)).isTrue();
      assertThat(database.queryForObject("SELECT email_verified FROM " + schema + ".users WHERE id=?", Boolean.class, user)).isFalse();
      upgrade.validate();
    } finally {
      database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test void upgradesCc8943abSchemaWithoutOutOfOrderAndPreservesExistingData() {
    String schema = "job_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var database = new JdbcTemplate(dataSource);
    try {
      Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
          .target("2026.10.24.82").load().migrate();
      database.update("INSERT INTO " + schema + ".users(first_name,last_name,username,email,password,role) "
          + "VALUES ('Baseline','Staff','baseline_staff','baseline@example.vn','fixture','STAFF')");
      long user = database.queryForObject("SELECT id FROM " + schema + ".users WHERE username='baseline_staff'", Long.class);
      long company = database.queryForObject("INSERT INTO " + schema
          + ".companies(name,tax_code,registration_certificate_url) VALUES ('Baseline','baseline-tax','https://example.vn/license') RETURNING id", Long.class);
      UUID event = UUID.randomUUID();
      database.update("INSERT INTO " + schema
          + ".notification_outbox(event_id,recipient_user_id,event_type,company_id,company_name,created_at,next_attempt_at) "
          + "VALUES (?,?,'MEMBERSHIP_REMOVED',?,'Baseline',now(),now())", event, user, company);
      database.update("INSERT INTO " + schema
          + ".user_notifications(source_event_id,recipient_user_id,event_type,company_id,company_name,created_at) "
          + "VALUES (?,?,'MEMBERSHIP_REMOVED',?,'Baseline',now())", event, user, company);
      var upgrade = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
      // Upgrade jobs, account-email proof storage, code attempts and Staff suspension.
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(4);
      upgrade.validate();
      assertThat(database.queryForObject("SELECT role FROM " + schema + ".users WHERE username='baseline_staff'", String.class))
          .isEqualTo("STAFF");
      assertThat(database.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=? "
          + "AND table_name='users' AND column_name='recruiting_email_verification_required'", Long.class, schema)).isZero();
      assertThat(database.queryForObject("SELECT email_verified FROM " + schema
          + ".users WHERE username='baseline_staff'", Boolean.class)).isTrue();
      assertThat(database.queryForObject("SELECT count(*) FROM " + schema + ".job_postings", Long.class)).isZero();
      assertThat(database.queryForObject("SELECT count(*) FROM " + schema
          + ".notification_outbox WHERE event_id=? AND job_id IS NULL AND revision_id IS NULL", Long.class, event)).isEqualTo(1);
      assertThat(database.queryForObject("SELECT count(*) FROM " + schema
          + ".user_notifications WHERE source_event_id=? AND job_id IS NULL AND revision_id IS NULL", Long.class, event)).isEqualTo(1);
      assertThat(upgrade.migrate().migrationsExecuted).isZero();
    } finally {
      database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }
}
