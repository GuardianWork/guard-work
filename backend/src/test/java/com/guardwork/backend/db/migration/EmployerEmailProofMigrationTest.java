package com.guardwork.backend.db.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class EmployerEmailProofMigrationTest {
    @Autowired DataSource dataSource;

    String migration() throws Exception {
        return new ClassPathResource("db/migration/V2026.10.27.84__bind_employer_email_proof.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    void fixture(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE TEMP TABLE users(id BIGINT PRIMARY KEY,email VARCHAR(255),email_verified BOOLEAN) ON COMMIT DROP");
            sql.execute("CREATE TEMP TABLE employer_profiles(user_id BIGINT,email_verified_at TIMESTAMPTZ,email_secret_hash VARCHAR(64)) ON COMMIT DROP");
        }
    }

    @Test
    void bindsOnlyActualProofAndPreservesOriginalEmailTimestampAndUnboundChallenge() throws Exception {
        try (var connection = dataSource.getConnection()) {
            fixture(connection);
            try (var sql = connection.createStatement()) {
                sql.execute("INSERT INTO users VALUES (1,'legacy@example.vn',true),(2,' Verified@Example.vn ',false),(3,'other@example.vn',true)");
                sql.execute("INSERT INTO employer_profiles VALUES (1,NULL,'retained-challenge'),(2,'2026-10-01T00:00:00Z',NULL)");
                sql.execute(migration());
                try (var rows = sql.executeQuery("SELECT email_verified_email,email_verified_at,email_secret_hash,email_challenge_email FROM employer_profiles ORDER BY user_id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isNull();
                    assertThat(rows.getTimestamp(2)).isNull();
                    assertThat(rows.getString(3)).isEqualTo("retained-challenge");
                    assertThat(rows.getString(4)).isNull();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("verified@example.vn");
                    assertThat(rows.getTimestamp(2).toInstant()).hasToString("2026-10-01T00:00:00Z");
                }
                try (var rows = sql.executeQuery("SELECT email,email_verified FROM users WHERE id=2")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo(" Verified@Example.vn ");
                    assertThat(rows.getBoolean(2)).isFalse();
                }
                assertThatThrownBy(() -> sql.execute("UPDATE users SET email='VERIFIED@example.vn' WHERE id=3"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("uq_users_normalized_email");
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
    }

    @Test
    void collisionsBlockWithoutCreatingPartialProofColumnsOrRewritingAccounts() throws Exception {
        try (var connection = dataSource.getConnection()) {
            fixture(connection);
            try (var sql = connection.createStatement()) {
                sql.execute("INSERT INTO users VALUES (1,'collision@example.vn',true),(2,' COLLISION@example.vn ',false)");
                var checkpoint = connection.setSavepoint();
                assertThatThrownBy(() -> sql.execute(migration())).isInstanceOf(SQLException.class)
                        .hasMessageContaining("normalized email collision");
                connection.rollback(checkpoint);
                try (var rows = sql.executeQuery("SELECT count(*) FROM pg_attribute WHERE attrelid='pg_temp.employer_profiles'::regclass AND attname='email_verified_email'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
    }
}
