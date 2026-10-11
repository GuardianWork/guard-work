package com.guardwork.backend;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

class TestDatabaseSafetyTest {
    @Test
    void startupRefusesDeveloperAndUnspecifiedDatabasesBeforeConnecting() {
        for (String url : new String[] {
                "jdbc:postgresql://127.0.0.1:5432/backend_db", "jdbc:postgresql://localhost/other_db", ""
        }) {
            try (var context = new GenericApplicationContext()) {
                TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "spring.datasource.url=" + url);
                assertThatThrownBy(() -> new TestDatabaseSafety().initialize(context))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("startup refused");
            }
        }
    }

    @Test
    void startupAllowsTheIsolatedDatabaseAndPerTicketVariants() {
        for (String url : new String[] {
                "jdbc:postgresql://127.0.0.1:55433/backend_test_db",
                "jdbc:postgresql://127.0.0.1:55432/backend_test_db_gw003"
        }) {
            try (var context = new GenericApplicationContext()) {
                TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "spring.datasource.url=" + url);
                assertThatCode(() -> new TestDatabaseSafety().initialize(context)).doesNotThrowAnyException();
            }
        }
    }
}
