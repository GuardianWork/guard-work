package com.guardwork.backend.db.migration;

import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class UnifiedRegistrationMigrationTest {
    @Autowired DataSource dataSource;

    @Test void emailProofMigrationPreservesVerificationValuesWithoutAnExemptionColumn() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                String schema = "ureg_" + UUID.randomUUID().toString().replace("-", "");
                sql.execute("CREATE SCHEMA " + schema);
                sql.execute("SET LOCAL search_path TO " + schema);
                sql.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, email_verified BOOLEAN NOT NULL DEFAULT FALSE)");
                sql.execute("INSERT INTO users VALUES (1,TRUE),(2,FALSE)");
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V2026.10.28.84__unified_registration_email_proof.sql"));
                sql.execute("INSERT INTO user_email_verification(user_id,email,secret_hash,issued_at,expires_at) "
                        + "VALUES (2,'test@example.vn','hash',now(),now()+interval '24 hours')");
                sql.execute("INSERT INTO users(id) VALUES (3)");
                try (var result = sql.executeQuery("SELECT email_verified FROM users ORDER BY id")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isFalse();
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isFalse();
                    assertThat(result.next()).isFalse();
                }
                try (var result = sql.executeQuery("SELECT email,secret_hash FROM user_email_verification WHERE user_id=2")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("test@example.vn");
                    assertThat(result.getString(2)).isEqualTo("hash");
                }
                try (var result = sql.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema='"
                        + schema + "' AND table_name='users' AND column_name='recruiting_email_verification_required'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isZero();
                }
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
    }
}
