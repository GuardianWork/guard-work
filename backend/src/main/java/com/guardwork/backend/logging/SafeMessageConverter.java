package com.guardwork.backend.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;

/** Protect console output when a framework logs a message alongside a raw exception. */
public class SafeMessageConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        if (event.getLoggerName().startsWith("com.guardwork.backend.") && event.getKeyValuePairs() != null) {
            for (var field : event.getKeyValuePairs()) {
                if (DiagnosticLog.CONTEXT_KEY.equals(field.key) && field.value instanceof DiagnosticEvent diagnostic) {
                    // Render registered constants, never the formatted message or arbitrary fields.
                    return diagnostic.fields() + " " + (event.getThrowableProxy() instanceof ThrowableProxy proxy
                            ? SafeException.describe(proxy.getThrowable()) : "origin=UNKNOWN");
                }
            }
        }
        if (event.getThrowableProxy() != null) {
            // Request summaries own stack traces; rendering the original event would
            // also expose exception messages embedded in the formatted message.
            if (!event.getMDCPropertyMap().containsKey("requestId")
                    && !event.getLoggerName().startsWith("org.apache.catalina.core.ContainerBase")
                    && event.getThrowableProxy() instanceof ThrowableProxy proxy) {
                return SafeException.describe(proxy.getThrowable());
            }
            return "exception_type=" + event.getThrowableProxy().getClassName()
                    + " exception_message=redacted";
        }
        return event.getFormattedMessage();
    }
}
