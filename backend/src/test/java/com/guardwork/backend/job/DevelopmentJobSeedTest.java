package com.guardwork.backend.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;

/** Exercises the actual development seed in a disposable schema of the existing test database. */
@SpringBootTest(properties = "company.membership.notifications.worker-enabled=false")
@ActiveProfiles("test")
class DevelopmentJobSeedTest {
  @Autowired DataSource dataSource;

  @Test void createsUsableJobsAndRerunsPreserveManualChangesAndRevokedAssignments() throws Exception {
    inSeedSchema((connection, database) -> {
      seed(connection);
      assertThat(database.queryForObject("SELECT count(*) FROM job_postings", Long.class)).isEqualTo(9);
      assertThat(database.queryForObject("SELECT count(*) FROM job_posting_revisions", Long.class)).isEqualTo(11);
      assertThat(database.queryForList("SELECT status FROM job_postings", String.class))
          .contains("PUBLISHED", "SUSPENDED", "PAUSED", "CLOSED", "EXPIRED", "DRAFT");
      assertThat(database.queryForObject("SELECT count(*) FROM job_posting_revisions WHERE state='PENDING_REVIEW'", Long.class)).isEqualTo(3);
      assertThat(database.queryForObject("SELECT count(*) FROM job_postings j JOIN job_posting_revisions r ON r.id=j.published_revision_id "
          + "WHERE r.state='APPROVED' AND (r.content->>'deadline')::timestamptz=j.approved_deadline", Long.class)).isEqualTo(7);
      assertThat(database.queryForObject("SELECT count(*) FROM job_assignments WHERE ended_at IS NULL", Long.class)).isEqualTo(9);
      assertThat(database.queryForObject("SELECT count(*) FROM audit_logs WHERE action='JOB_SUSPENDED'", Long.class)).isEqualTo(2);
      assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE event_type='JOB_EXPIRED' AND actor_user_id IS NULL", Long.class)).isEqualTo(1);
      assertThat(database.queryForObject("SELECT bool_and(approved_deadline>now()) FROM job_postings WHERE status='PUBLISHED'", Boolean.class)).isTrue();
      assertThat(database.queryForObject("SELECT count(*) FROM notification_outbox", Long.class)).isZero();

      long ready = database.queryForObject("SELECT job_id FROM job_audit_events WHERE facts->>'developmentFixture'='GW93-HIDE-READY'", Long.class);
      database.update("UPDATE job_postings SET status='PAUSED',version=version+1 WHERE id=?", ready);
      database.update("UPDATE users SET password='preserve-manual-password' WHERE username='demo_candidate'");
      database.update("UPDATE job_assignments SET ended_at=now(),ended_by_user_id=assigned_by_user_id,end_reason='UNASSIGNED'");
      database.update("UPDATE company_memberships SET status='REMOVED',ended_at=now(),ended_by_user_id=user_id WHERE role='RECRUITER'");
      database.update("UPDATE companies SET is_banned=true WHERE tax_code='GW-DEMO-LOCAL-001'");
      long eventCount = database.queryForObject("SELECT count(*) FROM job_audit_events", Long.class);
      long version = database.queryForObject("SELECT version FROM job_postings WHERE id=?", Long.class, ready);
      seed(connection);
      assertThat(database.queryForObject("SELECT count(*) FROM job_postings", Long.class)).isEqualTo(9);
      assertThat(database.queryForObject("SELECT count(*) FROM job_posting_revisions", Long.class)).isEqualTo(11);
      assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events", Long.class)).isEqualTo(eventCount);
      assertThat(database.queryForObject("SELECT count(*) FROM audit_logs WHERE action='JOB_SUSPENDED'", Long.class)).isEqualTo(2);
      assertThat(database.queryForObject("SELECT status FROM job_postings WHERE id=?", String.class, ready)).isEqualTo("PAUSED");
      assertThat(database.queryForObject("SELECT version FROM job_postings WHERE id=?", Long.class, ready)).isEqualTo(version);
      assertThat(database.queryForObject("SELECT password FROM users WHERE username='demo_candidate'", String.class)).isEqualTo("preserve-manual-password");
      assertThat(database.queryForObject("SELECT count(*) FROM job_assignments WHERE ended_at IS NULL", Long.class)).isZero();
      assertThat(database.queryForObject("SELECT count(*) FROM company_memberships WHERE role='RECRUITER' AND status='ACTIVE'", Long.class)).isZero();
      assertThat(database.queryForObject("SELECT is_banned FROM companies WHERE tax_code='GW-DEMO-LOCAL-001'", Boolean.class)).isTrue();
    });
  }

  @Test void duplicateFixtureMarkersFailWithoutDuplicatingOrResettingData() throws Exception {
    inSeedSchema((connection, database) -> {
      seed(connection);
      database.update("INSERT INTO job_audit_events(company_id,job_id,revision_id,actor_user_id,event_type,created_at,facts) "
          + "SELECT company_id,job_id,revision_id,actor_user_id,event_type,created_at,facts FROM job_audit_events "
          + "WHERE facts->>'developmentFixture'='GW93-HIDE-READY'");
      long count = database.queryForObject("SELECT count(*) FROM job_audit_events", Long.class);
      assertThatThrownBy(() -> seed(connection)).isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("Mock job identifier collision");
      try (var statement = connection.createStatement()) { statement.execute("ROLLBACK"); }
      assertThat(database.queryForObject("SELECT count(*) FROM job_postings", Long.class)).isEqualTo(9);
      assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events", Long.class)).isEqualTo(count);
    });
  }

  private void seed(Connection connection) throws Exception {
    try (var statement = connection.createStatement()) {
      statement.execute(Files.readString(Path.of("docker/dev-seed/seed.sql")));
    }
  }

  private void inSeedSchema(SeedCheck check) throws Exception {
    String schema = "dev_job_seed_" + UUID.randomUUID().toString().replace("-", "");
    try {
      Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
      try (var connection = dataSource.getConnection()) {
        String originalSchema = connection.getSchema();
        try {
          connection.setSchema(schema);
          check.run(connection, new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
        } finally {
          try (var statement = connection.createStatement()) { statement.execute("ROLLBACK"); }
          connection.setSchema(originalSchema);
        }
      }
    } finally {
      new JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @FunctionalInterface
  private interface SeedCheck {
    void run(Connection connection, JdbcTemplate database) throws Exception;
  }
}
