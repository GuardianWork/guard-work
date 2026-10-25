package com.guardwork.backend.logging;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Exception messages can contain credentials, SQL values, or rejected input. */
final class SafeException {

    private SafeException() {
    }

    static String origin(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        String origin = "UNKNOWN";
        int causes = 0;
        while (failure != null && visited.add(failure) && causes++ < 8) {
            for (StackTraceElement frame : failure.getStackTrace()) {
                if (frame.getClassName().startsWith("com.guardwork.backend.")) {
                    origin = frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
                    break;
                }
            }
            failure = failure.getCause();
        }
        return origin;
    }

    static String describe(Throwable failure) {
        String origin = origin(failure);
        StringBuilder result = new StringBuilder();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int causes = 0;
        while (failure != null && visited.add(failure) && causes++ < 8) {
            if (!result.isEmpty()) {
                result.append("\nCaused by: ");
            }
            result.append(failure.getClass().getName());
            StackTraceElement[] frames = failure.getStackTrace();
            for (int index = 0; index < Math.min(frames.length, 40); index++) {
                result.append("\n\tat ").append(frames[index]);
            }
            if (frames.length > 40) {
                result.append("\n\t... remaining frames omitted");
            }
            // Suppressed exceptions and all messages are intentionally excluded.
            failure = failure.getCause();
        }
        return "origin=" + origin + "\n" + result;
    }
}
