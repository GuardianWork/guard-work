package com.guardwork.backend.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class JobSearchHttpTest {
    @Autowired
    WebApplicationContext context;
    MockMvc http;

    @BeforeEach
    void setup() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void anonymousSearchReturnsSuccessPage() throws Exception {
        http.perform(get("/api/jobs/search"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray())
            .andExpect(jsonPath("$.data.pageSize").value(5));
    }

    @Test
    void invalidEnumerationReturnsBadRequest() throws Exception {
        http.perform(get("/api/jobs/search").param("employmentType", "not-a-type"))
            .andExpect(status().isBadRequest());
    }
}
