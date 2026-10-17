package com.guardwork.backend.logging;

import java.util.Objects;

import org.slf4j.Logger;

/** Log only exceptions handled here; let the request boundary log propagated failures. */
public final class DiagnosticLog {

    static final String CONTEXT_KEY = "safeDiagnostic";

    private DiagnosticLog() {
    }

    public static void error(Logger logger, DiagnosticEvent event, Throwable failure) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(failure, "failure");
        logger.atError().addKeyValue(CONTEXT_KEY, event).setCause(failure).log("Application diagnostic");
    }
}
