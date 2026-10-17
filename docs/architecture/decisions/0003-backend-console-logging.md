---
status: accepted
date: 2026-10-17
---

# Standardize backend console diagnostics with existing SLF4J and Logback

The project owner confirmed console-only backend operational logging, readable
text, safe request summaries, a server-generated response request ID, local
application DEBUG events, and sanitized exception reporting in the logging
design interview on 2026-10-17. The associated contract is documented in
[GW-SR-ADM-01](../../software-requirements/adm/backend-logging-spec.md).

Use the SLF4J/Logback stack already supplied by Spring Boot. An outer servlet
filter establishes MDC before Spring Security, measures elapsed time, logs one
completion summary, and removes its context. A non-handling MVC exception
resolver observes failures and returns null so existing response behavior remains
with the existing resolvers. Unexpected exceptions that escape the filter are
re-thrown unchanged.

Exception diagnostics contain class names and bounded stack frames, without
messages or suppressed exceptions. A console message converter also redacts
framework messages accompanied by a throwable. Known framework warnings that
embed rejected input, Hibernate database error values, and generated development
password logs are disabled. Framework throwable events within a request contain
only the class, because the request summary owns the sanitized trace. Tomcat's
post-dispatch servlet exception events also omit duplicate stacks.

## Follow-up: preserve registered application diagnostics

On 2026-10-17 the project owner confirmed Q1–Q6 of the follow-up interview:
use log-only domain-qualified codes, retain registered safe diagnostic context,
include originating application class/method/line, and give locally handled
exceptions an explicit sanitized stack. This updates the blanket request-only
trace ownership rule: propagated exceptions still belong to the request boundary,
while caught-and-handled failures belong to the explicit diagnostic call site.
Do not log then rethrow; no automatic deduplication is introduced.

Use a `DiagnosticEvent` enum registry carried as typed SLF4J structured metadata
through `DiagnosticLog.error`. The converter recognizes it only for application
loggers, renders registered constants, and ignores arbitrary event text and extra
fields. Framework throwable messages remain redacted. This avoids treating an
application package name alone as evidence that a formatted message is safe.
Origins prefer the deepest cause with an application frame, bounded and cycle-safe.
The log-code convention is documented in [error-codes.md](../../convention/error-codes.md).
Existing API catalogs and envelopes are preserved, including overlapping numbers.

Keep default verbosity at INFO and enable application DEBUG in an explicit local
profile. Request logging contains no personal identifiers or payloads. Database
auditing and its atomic immutable write requirements remain unchanged.

This decision adds no dependencies, database changes, file storage, or external
infrastructure. It intentionally reduces exception message detail in exchange for
keeping credentials and personal data out of console output. New asynchronous
endpoints will require a separate lifecycle/propagation decision before using
this synchronous request-completion implementation.
