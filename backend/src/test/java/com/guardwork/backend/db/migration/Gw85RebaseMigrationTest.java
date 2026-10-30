package com.guardwork.backend.db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Upgrade an existing dev database without rewriting committed migration history. */
@SpringBootTest
class Gw85RebaseMigrationTest {
    @Autowired DataSource dataSource;
    @TempDir Path devMigrations;

    @Test void appliesGw85MigrationsAfterCurrentDevAndPreservesExistingAccountProof() throws Exception {
        try (var scripts = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path script : scripts.toList()) {
                String name = script.getFileName().toString();
                if (!name.startsWith("V2026.10.27.83__") && !name.startsWith("V2026.10.27.84__")) {
                    Files.copy(script, devMigrations.resolve(script.getFileName()));
                }
            }
        }
        String schema = "gw85_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var database = new JdbcTemplate(dataSource);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("filesystem:" + devMigrations.toAbsolutePath()).load().migrate();
            long actor = database.queryForObject("INSERT INTO " + schema
                    + ".users(first_name,last_name,username,email,password,role,email_verified) "
                    + "VALUES ('Existing','User','existing_user','existing@example.vn','fixture','USER',true) RETURNING id", Long.class);
            database.update("INSERT INTO " + schema + ".candidate_profiles(user_id,full_name,phone_number) VALUES (?,'Existing User','0901234567')", actor);
            var upgrade = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .outOfOrder(true).load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(database.queryForObject("SELECT phone_number FROM " + schema + ".users WHERE id=?", String.class, actor))
                    .isEqualTo("+84901234567");
            assertThat(database.queryForObject("SELECT email_verified FROM " + schema + ".users WHERE id=?", Boolean.class, actor)).isTrue();
            assertThat(database.queryForObject("SELECT phone_number FROM " + schema + ".candidate_profiles WHERE user_id=?", String.class, actor))
                    .isEqualTo("0901234567");
            var normalStartup = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
            normalStartup.validate();
            assertThat(normalStartup.migrate().migrationsExecuted).isZero();
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
