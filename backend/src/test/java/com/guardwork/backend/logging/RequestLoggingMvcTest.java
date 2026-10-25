package com.guardwork.backend.logging;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.auth.security.JwtAuthenticationFilter;
import com.guardwork.backend.auth.security.RestAccessDeniedHandler;
import com.guardwork.backend.auth.security.RestAuthenticationEntryPoint;
import com.guardwork.backend.auth.security.SecurityConfig;
import com.guardwork.backend.auth.service.JwtService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequestLoggingMvcTest {

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;
    private static MockMvc baseline;
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    @BeforeAll
    static void createContext() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        FilterChainProxy security = context.getBean(FilterChainProxy.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new RequestLoggingFilter(), security).build();
        baseline = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void attachLogs() {
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(events);
        events.stop();
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void matchedRouteUsesTemplateAndPreservesResponsePayload() throws Exception {
        mvc.perform(post("/api/auth/logging-fixture/private-id")
                        .contentType("text/plain").content("password=payload-secret"))
                .andExpect(status().isOk())
                .andExpect(content().string("password=payload-secret"));

        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getFormattedMessage())
                .contains("route=/api/auth/logging-fixture/{id} status=200")
                .doesNotContain("private-id", "payload-secret");
    }

    @Test
    void existingExceptionResolverKeepsExpectedErrorResponseWithoutStackTrace() throws Exception {
        var expected = baseline.perform(get("/api/auth/logging-fixture/expected")).andReturn().getResponse();
        var actual = mvc.perform(get("/api/auth/logging-fixture/expected"))
                .andExpect(status().isBadRequest()).andReturn().getResponse();

        assertThat(actual.getErrorMessage()).isEqualTo(expected.getErrorMessage());
        assertThat(actual.getContentAsString()).isEqualTo(expected.getContentAsString());
        assertThat(actual.getHeader("X-Request-ID")).isNotBlank();
        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getLevel()).isEqualTo(Level.INFO);
        assertThat(events.list.getFirst().getFormattedMessage()).contains("status=400")
                .doesNotContain("exception-secret", "\n\tat ");
    }

    @Test
    void unexpectedExceptionStillEscapesWithOneSanitizedErrorSummary() {
        assertThatThrownBy(() -> mvc.perform(get("/api/auth/logging-fixture/unexpected")))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("exception-secret");

        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
        assertThat(events.list.getFirst().getFormattedMessage()).contains("status=500", "IllegalStateException")
                .doesNotContain("exception-secret");
    }

    @Test
    void resolved5xxKeepsItsStatusAndGetsSanitizedDiagnostics() throws Exception {
        mvc.perform(get("/api/auth/logging-fixture/unavailable")).andExpect(status().isServiceUnavailable());

        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
        assertThat(events.list.getFirst().getFormattedMessage()).contains("status=503", "ResponseStatusException")
                .doesNotContain("exception-secret");
    }

    @Test
    void unauthenticatedRequestIsCorrelatedBeforeSecurityRejectsIt() throws Exception {
        var expected = baseline.perform(get("/private-personal-path")).andReturn().getResponse();
        var actual = mvc.perform(get("/private-personal-path"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401))
                .andReturn().getResponse();

        assertThat(actual.getContentAsString()).isEqualTo(expected.getContentAsString());
        assertThat(actual.getHeader("X-Request-ID")).isNotBlank();
        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getFormattedMessage()).contains("route=UNMAPPED status=401")
                .doesNotContain("private-personal-path");
    }

    @Test
    void authenticatedForbiddenRequestPreservesSecurityResponse() throws Exception {
        String token = context.getBean(JwtService.class).generateAccessToken(1L, "fixture", "fixture@example.test", "USER");
        var expected = baseline.perform(get("/api/staff/logging-fixture")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse();
        var actual = mvc.perform(get("/api/staff/logging-fixture").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403))
                .andReturn().getResponse();

        assertThat(actual.getContentAsString()).isEqualTo(expected.getContentAsString());
        assertThat(actual.getHeader("X-Request-ID")).isNotBlank();
        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getLevel()).isEqualTo(Level.INFO);
        assertThat(events.list.getFirst().getFormattedMessage()).contains("status=403")
                .doesNotContain(token, "fixture@example.test");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({RequestExceptionObserver.class, SecurityConfig.class, JwtAuthenticationFilter.class,
            RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
    static class TestConfig {
        @Bean
        FixtureController fixtureController() {
            return new FixtureController();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        JwtService jwtService() {
            return new JwtService("logging_fixture_key_at_least_32_bytes_long", 60000, 600000);
        }
    }

    @RestController
    static class FixtureController {
        @PostMapping("/api/auth/logging-fixture/{id}")
        String echo(@PathVariable String id, @RequestBody String body) {
            return body;
        }

        @GetMapping("/api/auth/logging-fixture/expected")
        void expected() {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "exception-secret");
        }

        @GetMapping("/api/auth/logging-fixture/unexpected")
        void unexpected() {
            throw new IllegalStateException("exception-secret");
        }

        @GetMapping("/api/auth/logging-fixture/unavailable")
        void unavailable() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "exception-secret");
        }
    }
}
