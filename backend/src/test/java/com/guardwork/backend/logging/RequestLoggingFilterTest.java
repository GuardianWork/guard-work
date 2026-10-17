package com.guardwork.backend.logging;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    @BeforeEach
    void setUp() {
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(events);
        events.stop();
        MDC.clear();
    }

    @Test
    void correlatesLogsWithFreshResponseHeaderAndCleansOnlyItsContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/companies/private-person");
        request.addHeader("X-Request-ID", "untrusted-input");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put("otherContext", "preserved");

        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("requestId")).isEqualTo(response.getHeader("X-Request-ID"));
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/companies/{id}");
            response.setStatus(201);
        });

        String requestId = response.getHeader("X-Request-ID");
        assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
        assertThat(requestId).isNotEqualTo("untrusted-input");
        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getMDCPropertyMap()).containsEntry("requestId", requestId);
        assertThat(events.list.getFirst().getFormattedMessage())
                .contains("method=GET route=/companies/{id} status=201 duration_ms=")
                .doesNotContain("private-person", "untrusted-input");
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("otherContext")).isEqualTo("preserved");
    }

    @Test
    void excludesPayloadsHeadersQueryParametersAndUnmappedPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/reset/raw-path-token");
        request.setQueryString("token=query-secret");
        request.setContent("password=body-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer header-secret");
        request.addHeader("Cookie", "session=cookie-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            response.setStatus(401);
            response.getWriter().write("response-secret");
        });

        assertThat(events.list).hasSize(1);
        ILoggingEvent event = events.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains("route=UNMAPPED status=401")
                .doesNotContain("secret", "raw-path-token", "token=", "password=", "Bearer");
        assertThat(response.getContentAsString()).isEqualTo("response-secret");
    }

    @Test
    void logsEscapedServerFailureOnceWithoutMessagesAndRethrowsOriginal() {
        RuntimeException failure = new IllegalStateException("password=exception-secret",
                new IllegalArgumentException("refreshToken=cause-secret"));
        failure.addSuppressed(new IllegalStateException("resetToken=suppressed-secret"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/failure"), response,
                (req, res) -> { throw failure; }))
                .isSameAs(failure);

        assertThat(events.list).hasSize(1);
        ILoggingEvent event = events.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).contains("status=500", "java.lang.IllegalStateException",
                        "Caused by: java.lang.IllegalArgumentException", "\n\tat ",
                        "error_code=LOG:UNEXPECTED_FAILURE", "origin=com.guardwork.backend.logging.RequestLoggingFilterTest.")
                .doesNotContain("secret", "password=", "refreshToken=", "resetToken=", "Suppressed");
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(MDC.get("requestId")).isNull();
        // Logging observes failure; it must not rewrite the servlet response.
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void logsResolvedServerFailureAndStatusOnlyFailuresAtError() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/failure"), response, (req, res) -> {
            req.setAttribute(RequestExceptionObserver.EXCEPTION_ATTRIBUTE, new IllegalStateException("secret"));
            response.setStatus(503);
        });
        filter.doFilter(new MockHttpServletRequest("GET", "/failure"), response,
                (req, res) -> response.setStatus(502));

        assertThat(events.list).hasSize(2).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
        assertThat(events.list.getFirst().getFormattedMessage()).contains("status=503", "IllegalStateException")
                .doesNotContain("secret");
        assertThat(events.list.getLast().getFormattedMessage()).contains("status=502", "origin=UNKNOWN",
                "error_code=LOG:UNEXPECTED_FAILURE").doesNotContain("\n");
    }

    @Test
    void neverReusesIdsAcrossRequestsOnTheSameThread() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/"), first, (req, res) -> { });
        filter.doFilter(new MockHttpServletRequest("GET", "/"), second, (req, res) -> { });

        assertThat(first.getHeader("X-Request-ID")).isNotBlank().isNotEqualTo(second.getHeader("X-Request-ID"));
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void replacesUnrecognizedHttpMethodsWithFixedValue() throws Exception {
        filter.doFilter(new MockHttpServletRequest("secret-method", "/"), new MockHttpServletResponse(),
                (req, res) -> { });
        assertThat(events.list.getFirst().getFormattedMessage()).contains("method=OTHER")
                .doesNotContain("secret-method");
    }
}
