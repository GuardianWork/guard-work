package com.guardwork.backend.logging;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", requestId);
        MDC.put("requestId", requestId);
        long started = System.nanoTime();
        Throwable escaped = null;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error exception) {
            escaped = exception;
            throw exception;
        } finally {
            try {
                Throwable failure = escaped != null ? escaped
                        : (Throwable) request.getAttribute(RequestExceptionObserver.EXCEPTION_ATTRIBUTE);
                int status = escaped == null ? response.getStatus()
                        : escaped instanceof ErrorResponse error ? error.getStatusCode().value() : 500;
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String summary = "event=http_request_completed method=" + safeMethod(request.getMethod())
                        + " route=" + (route != null ? route : "UNMAPPED")
                        + " status=" + status + " duration_ms=" + durationMs;
                if (status >= 500) {
                    log.error("{} {}{}", summary, DiagnosticEvent.UNEXPECTED_FAILURE.errorFields(),
                            failure != null ? " " + SafeException.describe(failure) : " origin=UNKNOWN");
                } else {
                    log.info("{}", summary);
                }
            } finally {
                MDC.remove("requestId");
            }
        }
    }

    private static String safeMethod(String method) {
        return switch (method) {
            case "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE" -> method;
            default -> "OTHER";
        };
    }
}
