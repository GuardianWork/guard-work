package com.guardwork.backend.db.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class CanonicalAccountPhoneMigrationTest {
    @Autowired DataSource dataSource;

    private String migration() throws Exception {
        return new ClassPathResource("db/migration/V2026.10.27.83__canonical_account_phone.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private void fixture(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE TEMP TABLE users (id BIGINT PRIMARY KEY) ON COMMIT DROP");
            sql.execute("CREATE TEMP TABLE candidate_profiles (user_id BIGINT, phone_number VARCHAR(20)) ON COMMIT DROP");
            sql.execute("CREATE TEMP TABLE employer_profiles (user_id BIGINT, phone VARCHAR(20), phone_verified_at TIMESTAMPTZ) ON COMMIT DROP");
            sql.execute("INSERT INTO users VALUES (1),(2),(3),(4)");
        }
    }

    @Test
    void backfillsEquivalentContactsPreservesHistoryAndAllowsMultipleAbsentPhones() throws Exception {
        try (var connection = dataSource.getConnection()) {
            fixture(connection);
            try (var sql = connection.createStatement()) {
                sql.execute("INSERT INTO candidate_profiles VALUES (1,' 0901234567 '),(2,NULL)");
                sql.execute("INSERT INTO employer_profiles VALUES (1,'+84901234567','2026-10-01T00:00:00Z'),(2,'0987654321',NULL)");
                sql.execute(migration());
                try (var rows = sql.executeQuery("SELECT phone_number FROM users ORDER BY id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("+84901234567");
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("+84987654321");
                    for (int i = 0; i < 2; i++) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isNull();
                    }
                }
                try (var rows = sql.executeQuery("SELECT c.phone_number, e.phone, e.phone_verified_at FROM candidate_profiles c JOIN employer_profiles e USING(user_id)")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo(" 0901234567 ");
                    assertThat(rows.getString(2)).isEqualTo("+84901234567");
                    assertThat(rows.getTimestamp(3).toInstant()).hasToString("2026-10-01T00:00:00Z");
                }
                var checkpoint = connection.setSavepoint();
                assertThatThrownBy(() -> sql.execute("UPDATE users SET phone_number='+84901234567' WHERE id=2"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("uq_users_phone_number");
                connection.rollback(checkpoint);
                assertThatThrownBy(() -> sql.execute("UPDATE users SET phone_number='0901234567' WHERE id=2"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("ck_users_phone_number");
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "1,0901234567,1,0901234568,differing contacts",
            "1,0901234567,2,+84901234567,cross-User collision",
            "1,0123456789,2,,invalid contacts",
            "1,' ',2,,invalid contacts"
    })
    void inconsistentHistoryBlocksWithoutPartialConversion(long candidate, String candidatePhone,
            long employer, String employerPhone, String reason) throws Exception {
        try (var connection = dataSource.getConnection()) {
            fixture(connection);
            try (var insert = connection.prepareStatement("INSERT INTO candidate_profiles VALUES (?,?)")) {
                insert.setLong(1, candidate); insert.setString(2, candidatePhone); insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("INSERT INTO employer_profiles(user_id,phone) VALUES (?,?)")) {
                insert.setLong(1, employer); insert.setString(2, employerPhone); insert.executeUpdate();
            }
            try (var sql = connection.createStatement()) {
                var checkpoint = connection.setSavepoint();
                assertThatThrownBy(() -> sql.execute(migration()))
                        .isInstanceOf(SQLException.class).hasMessageContaining(reason);
                connection.rollback(checkpoint);
                try (var rows = sql.executeQuery("SELECT count(*) FROM pg_attribute WHERE attrelid='pg_temp.users'::regclass AND attname='phone_number'")) {
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
