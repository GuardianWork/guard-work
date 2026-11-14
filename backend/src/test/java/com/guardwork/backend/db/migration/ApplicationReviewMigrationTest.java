package com.guardwork.backend.db.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL migration boundary for Application review history and notification context. */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationReviewMigrationTest {

    private static final String MIGRATION =
            "db/migration/V2026.11.03.101__application_review_history_and_notifications.sql";
    private static final String INTERVIEW_MIGRATION =
            "db/migration/V2026.11.10.103__application_interview_stage.sql";
    private static final String HIRING_MIGRATION =
            "db/migration/V2026.11.10.104__application_hiring_confirmation.sql";
    private static final UUID CANDIDATE_ID = UUID.fromString("f154d665-bce2-4bcb-b7a9-22be2981121c");
    private static final UUID APPLICATION_ID = UUID.fromString("b50ab773-a38a-4e5a-8fb3-0ef717bb2ddf");

    @Autowired DataSource dataSource;

    @Test
    void migrationBackfillsSubmittedHistoryFromCandidateAndSubmissionTime() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                useIsolatedSchema(sql);
                createLegacySchema(sql);
                sql.execute("INSERT INTO users(id) VALUES (1001)");
                sql.execute("INSERT INTO candidate_profiles(id,user_id) VALUES ('" + CANDIDATE_ID + "',1001)");
                sql.execute("INSERT INTO applications(id,candidate_id,status,applied_at) " +
                        "VALUES ('" + APPLICATION_ID + "','" + CANDIDATE_ID + "','SUBMITTED','2026-10-15T12:34:56Z')");

                executeMigration(sql);

                try (var result = sql.executeQuery("SELECT from_status,to_status,actor_user_id,occurred_at " +
                        "FROM application_status_history WHERE application_id='" + APPLICATION_ID + "'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("from_status")).isNull();
                    assertThat(result.getString("to_status")).isEqualTo("SUBMITTED");
                    assertThat(result.getLong("actor_user_id")).isEqualTo(1001L);
                    assertThat(result.getTimestamp("occurred_at").toInstant())
                            .isEqualTo(java.time.Instant.parse("2026-10-15T12:34:56Z"));
                    assertThat(result.next()).isFalse();
                }
                sql.execute("UPDATE applications SET status='UNDER_REVIEW' WHERE id='" + APPLICATION_ID + "'");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void migrationStopsWhenLegacyDecisionHasNoTruthfulHistory() throws Exception {
        for (String legacyStatus : new String[]{"VIEWED", "REJECTED"}) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (var sql = connection.createStatement()) {
                    useIsolatedSchema(sql);
                    sql.execute("CREATE TABLE applications (id UUID PRIMARY KEY, status VARCHAR(30) NOT NULL, " +
                            "CONSTRAINT applications_status_check CHECK (status IN ('SUBMITTED','VIEWED','REJECTED'))) ");
                    sql.execute("INSERT INTO applications(id,status) VALUES ('" + UUID.randomUUID() + "','" + legacyStatus + "')");

                    assertThatThrownBy(() -> executeMigration(sql))
                            .hasMessageContaining("Application review migration requires reconciliation");
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    @Test
    void interviewMigrationPreservesSubmittedHistoryAndAllowsInterviewEvents() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                useIsolatedSchema(sql);
                createLegacySchema(sql);
                sql.execute("INSERT INTO users(id) VALUES (1001)");
                sql.execute("INSERT INTO candidate_profiles(id,user_id) VALUES ('" + CANDIDATE_ID + "',1001)");
                sql.execute("INSERT INTO applications(id,candidate_id,status,applied_at) " +
                        "VALUES ('" + APPLICATION_ID + "','" + CANDIDATE_ID + "','SUBMITTED','2026-10-15T12:34:56Z')");

                executeMigration(sql);
                executeInterviewMigration(sql);

                sql.execute("UPDATE applications SET status='UNDER_REVIEW' WHERE id='" + APPLICATION_ID + "'");
                sql.execute("UPDATE applications SET status='INTERVIEWING' WHERE id='" + APPLICATION_ID + "'");
                sql.execute("INSERT INTO application_status_history(application_id,from_status,to_status,actor_user_id,occurred_at) " +
                        "VALUES ('" + APPLICATION_ID + "','UNDER_REVIEW','INTERVIEWING',1001,now())");
                sql.execute("INSERT INTO notification_outbox(id,recipient_user_id,event_type,company_id,company_name,created_at, " +
                        "next_attempt_at,application_id) VALUES (1,1001,'APPLICATION_INTERVIEWING',1,'Công ty Ánh',now(),now(),'" +
                        APPLICATION_ID + "')");
                sql.execute("INSERT INTO user_notifications(id,recipient_user_id,event_type,company_id,company_name,created_at,application_id) " +
                        "VALUES (1,1001,'APPLICATION_INTERVIEWING',1,'Công ty Ánh',now(),'" + APPLICATION_ID + "')");

                try (var result = sql.executeQuery("SELECT count(*) FROM application_status_history WHERE application_id='" +
                        APPLICATION_ID + "' AND from_status IS NULL AND to_status='SUBMITTED'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(1);
                }
                try (var result = sql.executeQuery("SELECT status FROM applications WHERE id='" + APPLICATION_ID + "'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("INTERVIEWING");
                }

                assertThatThrownBy(() -> sql.execute("INSERT INTO application_status_history(" +
                        "application_id,from_status,to_status,actor_user_id,occurred_at) VALUES ('" + APPLICATION_ID +
                        "','INTERVIEWING','UNDER_REVIEW',1001,now())"))
                        .hasMessageContaining("ck_application_history_status");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void hiringMigrationPreservesHistoryAndAllowsHiredStateEdgesAndNotifications() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                useIsolatedSchema(sql);
                createLegacySchema(sql);
                sql.execute("INSERT INTO users(id) VALUES (1001)");
                sql.execute("INSERT INTO candidate_profiles(id,user_id) VALUES ('" + CANDIDATE_ID + "',1001)");
                sql.execute("INSERT INTO applications(id,candidate_id,status,applied_at) " +
                        "VALUES ('" + APPLICATION_ID + "','" + CANDIDATE_ID + "','SUBMITTED','2026-10-15T12:34:56Z')");

                executeMigration(sql);
                executeInterviewMigration(sql);
                executeHiringMigration(sql);

                sql.execute("UPDATE applications SET status='UNDER_REVIEW' WHERE id='" + APPLICATION_ID + "'");
                sql.execute("UPDATE applications SET status='HIRED' WHERE id='" + APPLICATION_ID + "'");
                sql.execute("INSERT INTO application_status_history(application_id,from_status,to_status,actor_user_id,occurred_at) " +
                        "VALUES ('" + APPLICATION_ID + "','UNDER_REVIEW','HIRED',1001,now())");
                sql.execute("INSERT INTO notification_outbox(id,recipient_user_id,event_type,company_id,company_name,created_at, " +
                        "next_attempt_at,application_id) VALUES (1,1001,'APPLICATION_HIRED',1,'Công ty Ánh',now(),now(),'" +
                        APPLICATION_ID + "')");
                sql.execute("INSERT INTO user_notifications(id,recipient_user_id,event_type,company_id,company_name,created_at,application_id) " +
                        "VALUES (1,1001,'APPLICATION_HIRED',1,'Công ty Ánh',now(),'" + APPLICATION_ID + "')");

                try (var result = sql.executeQuery("SELECT status FROM applications WHERE id='" + APPLICATION_ID + "'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("HIRED");
                }
                try (var result = sql.executeQuery("SELECT count(*) FROM application_status_history WHERE application_id='" +
                        APPLICATION_ID + "' AND from_status IS NULL AND to_status='SUBMITTED'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(1);
                }

                assertThatThrownBy(() -> sql.execute("INSERT INTO application_status_history(" +
                        "application_id,from_status,to_status,actor_user_id,occurred_at) VALUES ('" + APPLICATION_ID +
                        "','HIRED','REJECTED',1001,now())"))
                        .hasMessageContaining("ck_application_history_status");
            } finally {
                connection.rollback();
            }
        }
    }

    private static void createLegacySchema(java.sql.Statement sql) throws SQLException {
        sql.execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
        sql.execute("CREATE TABLE candidate_profiles (id UUID PRIMARY KEY,user_id BIGINT NOT NULL)");
        sql.execute("CREATE TABLE applications (id UUID PRIMARY KEY,candidate_id UUID NOT NULL, " +
                "status VARCHAR(30) NOT NULL,applied_at TIMESTAMPTZ NOT NULL, " +
                "CONSTRAINT applications_status_check CHECK (status IN ('SUBMITTED','VIEWED','REJECTED'))) ");
        sql.execute("CREATE TABLE notification_outbox (id BIGINT PRIMARY KEY,recipient_user_id BIGINT NOT NULL, " +
                "event_type VARCHAR(50) NOT NULL,company_id BIGINT,company_name VARCHAR(255),job_id BIGINT,revision_id BIGINT, " +
                "created_at TIMESTAMPTZ NOT NULL,delivered_at TIMESTAMPTZ,attempt_count INTEGER NOT NULL DEFAULT 0, " +
                "next_attempt_at TIMESTAMPTZ,error_category VARCHAR(80), " +
                "CONSTRAINT notification_outbox_event_type_check CHECK (event_type IN ('RECRUITER_ACCEPTED'," +
                "'MEMBERSHIP_REMOVED','JOB_ASSIGNED','JOB_UNASSIGNED','JOB_REVISION_APPROVED'," +
                "'JOB_REVISION_REJECTED','JOB_NEEDS_REASSIGNMENT'))) ");
        sql.execute("CREATE TABLE user_notifications (id BIGINT PRIMARY KEY,recipient_user_id BIGINT NOT NULL, " +
                "event_type VARCHAR(50) NOT NULL,company_id BIGINT,company_name VARCHAR(255),job_id BIGINT,revision_id BIGINT, " +
                "created_at TIMESTAMPTZ NOT NULL,read_at TIMESTAMPTZ, " +
                "CONSTRAINT user_notifications_event_type_check CHECK (event_type IN ('RECRUITER_ACCEPTED'," +
                "'MEMBERSHIP_REMOVED','JOB_ASSIGNED','JOB_UNASSIGNED','JOB_REVISION_APPROVED'," +
                "'JOB_REVISION_REJECTED','JOB_NEEDS_REASSIGNMENT'))) ");
    }

    private static void useIsolatedSchema(java.sql.Statement sql) throws SQLException {
        String schema = "application_review_test_" + UUID.randomUUID().toString().replace("-", "");
        sql.execute("CREATE SCHEMA " + schema);
        sql.execute("SET LOCAL search_path TO " + schema + ",public");
    }

    private static void executeMigration(java.sql.Statement sql) throws Exception {
        try (var input = new ClassPathResource(MIGRATION).getInputStream()) {
            sql.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void executeInterviewMigration(java.sql.Statement sql) throws Exception {
        try (var input = new ClassPathResource(INTERVIEW_MIGRATION).getInputStream()) {
            sql.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void executeHiringMigration(java.sql.Statement sql) throws Exception {
        try (var input = new ClassPathResource(HIRING_MIGRATION).getInputStream()) {
            sql.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
