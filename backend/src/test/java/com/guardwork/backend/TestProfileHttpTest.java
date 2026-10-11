package com.guardwork.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** No @ActiveProfiles: ordinary Maven runs must still select the isolated database profile. */
@SpringBootTest(properties = {
        "spring.datasource.url=${BACKEND_TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:55432/backend_test_db}",
        "spring.datasource.username=${BACKEND_TEST_DATABASE_USER:backend_test}",
        "spring.datasource.password=${BACKEND_TEST_DATABASE_PASSWORD:}"
})
class TestProfileHttpTest {
    @Autowired Environment environment;
    @Autowired WebApplicationContext context;

    @Test
    void ordinaryTestRunUsesIsolatedProfileAndRealCompanySecurity() throws Exception {
        assertThat(environment.getActiveProfiles()).contains("test");
        MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build()
                .perform(post("/api/companies/verification-request")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
