package com.guardwork.backend.db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class MigrationVersionTest {
    @Test
    void versionedMigrationsHaveUniqueFlywayVersions() throws Exception {
        Map<MigrationVersion, ArrayList<String>> migrations = new TreeMap<>();
        var resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql");
        assertThat(resources).isNotEmpty();
        for (var resource : resources) {
            String filename = resource.getFilename();
            var version = MigrationVersion.fromVersion(filename.substring(1, filename.indexOf("__")));
            migrations.computeIfAbsent(version, ignored -> new ArrayList<>()).add(filename);
        }
        assertThat(migrations).allSatisfy((version, files) ->
                assertThat(files).as("Flyway version %s", version).hasSize(1));
    }
}
