package com.guardwork.backend.logging;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.KeyValuePair;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleLoggingTest {

    private final LoggerContext context = new LoggerContext();
    private PatternLayoutEncoder encoder;

    @BeforeEach
    void loadActualConsoleConfiguration() throws Exception {
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(getClass().getResource("/logback-spring.xml"));
        assertThat(context.getStatusManager().getCopyOfStatusList())
                .noneMatch(status -> status.getLevel() == Status.ERROR);
        ConsoleAppender<?> console = (ConsoleAppender<?>) context.getLogger(Logger.ROOT_LOGGER_NAME)
                .getAppender("CONSOLE");
        encoder = (PatternLayoutEncoder) console.getEncoder();
    }

    @AfterEach
    void closeContext() {
        context.stop();
    }

    @Test
    void formatsReadableUtf8OutputWithVietnamOffsetAndCorrelation() {
        LoggingEvent event = event("com.guardwork.backend.Example", "event=example outcome=received Tiếng Việt", null,
                Map.of("requestId", "fixture-request-id"));

        assertThat(encoder.getCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(encoder.getLayout().doLayout(event)).contains("+07:00", "INFO", "Example",
                "[requestId=fixture-request-id]", "Tiếng Việt").doesNotContain("%PARSER_ERROR");
    }

    @Test
    void sanitizesFrameworkThrowableMessagesAndDoesNotRepeatRequestStacks() {
        IllegalStateException failure = new IllegalStateException("password=exception-secret",
                new IllegalArgumentException("token=cause-secret"));
        LoggingEvent event = event("org.springframework.example.Framework", "Failure: exception-secret", failure,
                Map.of("requestId", "fixture-request-id"));

        assertThat(encoder.getLayout().doLayout(event)).contains("exception_type=java.lang.IllegalStateException")
                .doesNotContain("exception-secret", "cause-secret", "password=", "token=", "\tat ");
    }

    @Test
    void sanitizesStartupExceptionAndRetainsStackFramesOutsideRequests() {
        IllegalArgumentException failure = new IllegalArgumentException("databasePassword=startup-secret");
        failure.addSuppressed(new RuntimeException("suppressed-secret"));
        LoggingEvent event = event("com.guardwork.backend.Startup", "startup-secret", failure, Map.of());

        assertThat(encoder.getLayout().doLayout(event)).contains("[requestId=none]", "IllegalArgumentException", "\tat ")
                .doesNotContain("startup-secret", "suppressed-secret", "databasePassword=", "Suppressed");
    }

    @Test
    void tomcatPostDispatchExceptionDoesNotLeakMessageOrRepeatStack() {
        LoggingEvent event = event("org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/]",
                "Servlet.service threw exception: container-secret", new IllegalStateException("container-secret"), Map.of());

        assertThat(encoder.getLayout().doLayout(event)).contains("IllegalStateException")
                .doesNotContain("container-secret", "\tat ");
    }

    @Test
    void cyclicExceptionCausesDoNotHangOrExposeMessages() {
        RuntimeException first = new RuntimeException("first-secret");
        RuntimeException second = new RuntimeException("second-secret");
        first.initCause(second);
        second.initCause(first);

        String diagnostic = SafeException.describe(first);
        assertThat(diagnostic).contains("RuntimeException", "Caused by:").doesNotContain("first-secret", "second-secret");
        assertThat(diagnostic.split("Caused by:")).hasSize(2);
    }

    @Test
    void handledApplicationFailureKeepsRegisteredContextAndSanitizedStackInsideRequest() {
        IllegalArgumentException cause = new IllegalArgumentException("token=cause-secret");
        cause.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.guardwork.backend.auth.AuthService", "login", "AuthService.java", 88) });
        IllegalStateException failure = new IllegalStateException("password=exception-secret", cause);
        LoggingEvent event = event("com.guardwork.backend.auth.AuthService", "email=message-secret", failure,
                Map.of("requestId", "fixture-request-id"));
        event.setKeyValuePairs(List.of(new KeyValuePair("safeDiagnostic", DiagnosticEvent.AUTH_LOGIN_REJECTED),
                new KeyValuePair("email", "key-value-secret")));

        assertThat(encoder.getLayout().doLayout(event))
                .contains("event=auth_login_rejected", "operation=authenticate", "error_code=AUTH:40101",
                        "error_name=INVALID_CREDENTIALS", "origin=com.guardwork.backend.auth.AuthService.login:88",
                        "IllegalStateException", "Caused by: java.lang.IllegalArgumentException", "\tat ")
                .doesNotContain("exception-secret", "cause-secret", "message-secret", "key-value-secret");
    }

    @Test
    void untrustedStringsAndFrameworkLoggersCannotOptIntoSafeDiagnostics() {
        LoggingEvent untrusted = event("com.guardwork.backend.Example", "secret", new RuntimeException("secret"),
                Map.of("requestId", "fixture"));
        untrusted.setKeyValuePairs(List.of(new KeyValuePair("safeDiagnostic", "event=secret")));
        LoggingEvent framework = event("org.springframework.Example", "secret", new RuntimeException("secret"),
                Map.of("requestId", "fixture"));
        framework.setKeyValuePairs(List.of(new KeyValuePair("safeDiagnostic", DiagnosticEvent.AUTH_LOGIN_REJECTED)));

        assertThat(encoder.getLayout().doLayout(untrusted)).contains("exception_message=redacted")
                .doesNotContain("secret", "event=", "\tat ");
        assertThat(encoder.getLayout().doLayout(framework)).contains("exception_message=redacted")
                .doesNotContain("secret", "event=", "\tat ");
    }

    @Test
    void originUsesDeepestApplicationCauseThenFallsBackThroughWrappers() {
        RuntimeException libraryCause = new RuntimeException("secret");
        libraryCause.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.example.Driver", "execute", "Driver.java", 5) });
        RuntimeException wrapper = new RuntimeException("secret", libraryCause);
        wrapper.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.example.Proxy", "invoke", "Proxy.java", 1),
                new StackTraceElement("com.guardwork.backend.company.CompanyService", "register", "CompanyService.java", 97) });

        assertThat(SafeException.origin(wrapper)).isEqualTo("com.guardwork.backend.company.CompanyService.register:97");
        assertThat(SafeException.origin(libraryCause)).isEqualTo("UNKNOWN");
        assertThat(SafeException.origin(null)).isEqualTo("UNKNOWN");
        libraryCause.initCause(wrapper);
        assertThat(SafeException.origin(wrapper)).isEqualTo("com.guardwork.backend.company.CompanyService.register:97");
    }

    @Test
    void codesRemainDistinctAcrossDomainsEvenWhenNumbersMatch() {
        assertThat(DiagnosticEvent.AUTH_REGISTRATION_CONFLICT.fields()).contains("error_code=AUTH:40901",
                "error_name=USERNAME_OR_EMAIL_EXISTS");
        assertThat(DiagnosticEvent.COMPANY_REGISTRATION_CONFLICT.fields()).contains("error_code=COMPANY:40901",
                "error_name=DUPLICATE_TAX_CODE");
    }

    @Test
    void publicDiagnosticApiRetainsContextAndStackForHandledFailureWithoutChangingRequestOutcome() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("com.guardwork.backend.logging.HandledFailureFixture");
        ListAppender<ILoggingEvent> captured = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        boolean additive = logger.isAdditive();
        captured.start();
        logger.setAdditive(false);
        logger.addAppender(captured);
        var request = new MockHttpServletRequest("GET", "/fixture");
        var response = new MockHttpServletResponse();
        try {
            new RequestLoggingFilter().doFilter(request, response, (req, res) -> {
                try {
                    throw new IllegalStateException("password=handled-secret");
                } catch (IllegalStateException failure) {
                    DiagnosticLog.error(logger, DiagnosticEvent.UNEXPECTED_FAILURE, failure);
                }
                response.setStatus(200);
            });

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(captured.list).hasSize(1);
            String rendered = encoder.getLayout().doLayout(captured.list.getFirst());
            assertThat(rendered).contains("[requestId=" + response.getHeader("X-Request-ID") + "]",
                    "event=handled_failure", "operation=handle_failure", "error_code=LOG:UNEXPECTED_FAILURE",
                    "origin=com.guardwork.backend.logging.ConsoleLoggingTest.", "IllegalStateException", "\tat ")
                    .doesNotContain("handled-secret", "password=", "Application diagnostic");
            assertThat(rendered.split("java.lang.IllegalStateException", -1)).hasSize(2);
            assertThat(MDC.get("requestId")).isNull();
        } finally {
            logger.detachAppender(captured);
            logger.setAdditive(additive);
            captured.stop();
        }
    }

    private LoggingEvent event(String logger, String message, Throwable failure, Map<String, String> mdc) {
        LoggingEvent event = new LoggingEvent(getClass().getName(), context.getLogger(logger),
                failure != null ? Level.ERROR : Level.INFO, message, failure, null);
        event.setMDCPropertyMap(mdc);
        return event;
    }
}
