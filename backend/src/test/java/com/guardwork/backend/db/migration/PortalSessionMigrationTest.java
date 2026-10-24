package com.guardwork.backend.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PortalSessionMigrationTest {
    @Autowired DataSource dataSource;

    @Test
    void backfillBindsExistingSessionsToPlatformRolesAndConstrainsNewSessions() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                sql.execute("CREATE TEMP TABLE users (id BIGINT PRIMARY KEY, role VARCHAR(30)) ON COMMIT DROP");
                sql.execute("CREATE TEMP TABLE refresh_tokens (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, token_hash VARCHAR(64), family_id VARCHAR(36)) ON COMMIT DROP");
                sql.execute("INSERT INTO users VALUES (1,'STAFF'),(2,'USER')");
                sql.execute("INSERT INTO refresh_tokens VALUES (11,1,'staff-hash','staff-family'),(12,2,'user-hash','user-family')");
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V2026.10.24.82__bind_refresh_tokens_to_portal.sql"));
                try (var result = sql.executeQuery("SELECT portal,token_hash,family_id FROM refresh_tokens ORDER BY id")) {
                    for (String prefix : new String[]{"staff", "user"}) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString(1)).isEqualTo(prefix.equals("staff") ? "staff" : "public");
                        assertThat(result.getString(2)).isEqualTo(prefix + "-hash");
                        assertThat(result.getString(3)).isEqualTo(prefix + "-family");
                    }
                }
                var checkpoint = connection.setSavepoint();
                assertThatThrownBy(() -> sql.execute("INSERT INTO refresh_tokens(id,user_id,portal) VALUES (13,2,'admin')"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("refresh_tokens_portal_check");
                connection.rollback(checkpoint);
                assertThatThrownBy(() -> sql.execute("INSERT INTO refresh_tokens(id,user_id) VALUES (13,2)"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("portal");
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
    }
}
