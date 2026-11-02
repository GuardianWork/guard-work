package com.guardwork.backend.report;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

/** Uses the repository's transaction-local PostgreSQL migration boundary. */
@SpringBootTest
class UserReportMigrationTest {
  @Autowired DataSource dataSource;

  @Test void migrationsPreserveUsersAndEnforceReportAndEvidenceConstraints() throws Exception {
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var sql = connection.createStatement()) {
        sql.execute("CREATE TEMP TABLE users(id BIGINT PRIMARY KEY, marker TEXT)");
        sql.execute("INSERT INTO users VALUES (1,'retained user')");
        for (String file : new String[] {"V2026.11.01.99__user_report_intake.sql", "V2026.11.01.100__user_report_evidence.sql"}) {
          String migration = new ClassPathResource("db/migration/" + file).getContentAsString(StandardCharsets.UTF_8);
          sql.execute(migration.replace("CREATE TABLE ", "CREATE TEMP TABLE "));
        }
        sql.execute("INSERT INTO user_reports(id,reporter_id,target_type,target_id,complaint,status,created_at) "
            + "VALUES (1,1,'CANDIDATE','b94bac17-8b8b-48f8-bb72-c924ca6a2a42','Reported complaint','OPEN',now())");
        for (String statement : new String[] {
            "UPDATE user_reports SET status='INVALID' WHERE id=1",
            "UPDATE user_reports SET target_type='USER' WHERE id=1",
            "UPDATE user_reports SET reporter_id=999 WHERE id=1",
            "UPDATE user_reports SET complaint='short' WHERE id=1",
            "UPDATE user_reports SET complaint=repeat('x',5001) WHERE id=1"}) {
          rejects(connection, statement);
        }
        sql.execute("INSERT INTO user_report_evidence(id,report_id,filename,content_type,size_bytes,content_hash,content) "
            + "VALUES ('b94bac17-8b8b-48f8-bb72-c924ca6a2a42',1,'proof.pdf','application/pdf',5,'hash',decode('255044462d','hex'))");
        for (String statement : new String[] {
            "UPDATE user_report_evidence SET size_bytes=5242881",
            "UPDATE user_report_evidence SET size_bytes=0",
            "UPDATE user_report_evidence SET size_bytes=6",
            "UPDATE user_report_evidence SET content_type='image/svg+xml'",
            "UPDATE user_report_evidence SET report_id=999",
            "INSERT INTO user_report_evidence SELECT 'b94bac17-8b8b-48f8-bb72-c924ca6a2a43',report_id,filename,content_type,size_bytes,content_hash,content FROM user_report_evidence"}) {
          rejects(connection, statement);
        }
        sql.execute("UPDATE user_report_evidence SET content=NULL");
        try (var row = sql.executeQuery("SELECT marker FROM users WHERE id=1")) {
          assertThat(row.next()).isTrue();
          assertThat(row.getString(1)).isEqualTo("retained user");
        }
      } finally {
        connection.rollback();
        connection.setAutoCommit(true);
      }
    }
  }

  private void rejects(Connection connection, String statement) throws Exception {
    var checkpoint = connection.setSavepoint();
    try (var sql = connection.createStatement()) {
      assertThatThrownBy(() -> sql.execute(statement)).isInstanceOf(SQLException.class);
    } finally { connection.rollback(checkpoint); }
  }
}
