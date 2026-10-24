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
class PlatformStaffMigrationTest {
    @Autowired DataSource dataSource;

    @Test
    void migrationConvertsExistingPlatformAdminsWithoutChangingCompanyAdminMemberships() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                // Temporary tables shadow only this connection's tables and leave shared test data intact.
                sql.execute("CREATE TEMP TABLE users (id BIGINT PRIMARY KEY, role VARCHAR(30) NOT NULL, CONSTRAINT users_role_check CHECK(role IN ('USER','ADMIN'))) ON COMMIT DROP");
                sql.execute("CREATE TEMP TABLE company_memberships (id BIGINT PRIMARY KEY, user_id BIGINT, role VARCHAR(30) CHECK(role IN ('OWNER','ADMIN','RECRUITER'))) ON COMMIT DROP");
                sql.execute("INSERT INTO users VALUES (1,'ADMIN'),(2,'USER'),(3,'USER')");
                sql.execute("INSERT INTO company_memberships VALUES (1,1,'ADMIN'),(2,2,'OWNER'),(3,3,'RECRUITER')");
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V2026.10.24.81__rename_platform_admin_to_staff.sql"));
                try (var result = sql.executeQuery("SELECT role FROM users ORDER BY id")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("STAFF");
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("USER");
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("USER");
                }
                try (var result = sql.executeQuery("SELECT role FROM company_memberships ORDER BY id")) {
                    for (String role : new String[]{"ADMIN", "OWNER", "RECRUITER"}) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString(1)).isEqualTo(role);
                    }
                }
                assertThatThrownBy(() -> sql.execute("INSERT INTO users VALUES (4,'ADMIN')"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("users_role_check");
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
    }
}
