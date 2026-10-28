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

    @Test void rolloutPreservesBothLegacyVerificationValuesAndMarksOnlyLaterInsertions() throws Exception {
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
                sql.execute("INSERT INTO users(id) VALUES (3)");
                try (var result = sql.executeQuery("SELECT email_verified,recruiting_email_verification_required FROM users ORDER BY id")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                    assertThat(result.getBoolean(2)).isFalse();
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isFalse();
                    assertThat(result.getBoolean(2)).isFalse();
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isFalse();
                    assertThat(result.getBoolean(2)).isTrue();
                }
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
    }
}
