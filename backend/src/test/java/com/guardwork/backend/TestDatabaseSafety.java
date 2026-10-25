package com.guardwork.backend;

import java.net.URI;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Fail before Flyway or a datasource can touch an existing developer database. */
public class TestDatabaseSafety implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final Pattern TEST_DATABASE = Pattern.compile("/backend_test_db(?:_[a-z0-9]+)?");

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String configured = context.getEnvironment().getProperty("spring.datasource.url", "");
        try {
            if (!configured.startsWith("jdbc:postgresql://")) {
                throw new IllegalArgumentException();
            }
            URI database = URI.create(configured.substring("jdbc:".length()));
            if (database.getPath() == null || !TEST_DATABASE.matcher(database.getPath()).matches()) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalidDatabase) {
            throw new IllegalStateException("Backend tests require an isolated backend_test_db database; startup refused");
        }
    }
}
