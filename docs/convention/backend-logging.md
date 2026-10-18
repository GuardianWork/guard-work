# Backend logging standard

Scope: [GW-SR-ADM-01](../software-requirements/adm/backend-logging-spec.md).
Decision: [console logging ADR](../architecture/decisions/0003-backend-console-logging.md).
Error identity and handled exceptions: [error-code convention](error-codes.md).

## Run and configure

From `backend/`, opt into local application DEBUG events:

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

Without the local profile, application and framework logs default to INFO.
For a temporary application-only override, including while using the local profile:

```bash
LOGGING_LEVEL_COM_GUARDWORK_BACKEND=INFO ./mvnw spring-boot:run
```

`APP_LOG_LEVEL` also sets the default application level, but the local profile
overrides that default with DEBUG. Framework packages remain at INFO, except the
specific privacy exclusions in `application.properties`. Do not enable root,
Spring Web, Spring Security, or Hibernate DEBUG/TRACE casually: they can emit
payloads, SQL values, or credentials. SQL, bind/extract values, and database-error
value output stay disabled. No file appender or external collector is configured.

Example request summary:

```text
2026-10-17T14:05:22.103+07:00 INFO  [http-nio-8080-exec-1] c.g.backend.logging.RequestLoggingFilter [requestId=1be2ce6a-0d7a-4aee-b0f1-4019ba297fd6] event=http_request_completed method=GET route=/api/companies/{id} status=200 duration_ms=12
```

The timestamp uses `Asia/Ho_Chi_Minh`; output encoding is UTF-8. Non-request logs
show `requestId=none`. Find a request's `X-Request-ID` in browser network tools,
then search the console for the same UUID. Incoming request IDs are not trusted.
The header does not introduce authentication, alter the response body, or change
CORS policy. A security rejection or unmatched route can show `route=UNMAPPED`.

## Write logs

Use an SLF4J logger in the class that owns the diagnostic event. Prefer fixed
event names and fixed outcomes; use parameterized arguments for approved safe
values. Do not concatenate or serialize user-provided values.

```java
private static final Logger log = LoggerFactory.getLogger(CompanyService.class);

log.debug("event=company_registration outcome=saved_pending_verification");
```

| Level | Use |
|---|---|
| DEBUG | Selected application decisions/progress useful during local debugging. |
| INFO | One HTTP completion summary, including expected 4xx responses. |
| WARN | Actionable recoverable operational problems; expected validation/authentication failures do not need WARN. |
| ERROR | HTTP 5xx completion summaries and unexpected operational failures. |

Do not add routine controller/repository entry/exit logs. Do not catch and log an
exception at each layer. The request boundary owns propagated failure diagnostics.
For exceptions caught and handled locally, use
`DiagnosticLog.error(log, DiagnosticEvent.UNEXPECTED_FAILURE, failure)` to retain
registered context and a sanitized stack. Do not log then rethrow. Existing
MVC/security handlers continue to produce their existing responses.

Inside a transaction, a progress event records work performed so far; it does not
prove commit. Immutable database audit records remain the source of truth for
verification decisions. Never substitute operational logs for audit writes.

## Privacy and exception handling

Never log passwords, tokens, authorization headers, cookies, raw paths, query
parameters, request/response bodies, emails, names, IPs, user agents, DTOs, or
entities. Match route templates through Spring; unknown routes use a constant
fallback. Password reset requests must not reveal account existence in events.

Exception messages can contain rejected input or database values. HTTP 5xx
summaries include `error_code=LOG:UNEXPECTED_FAILURE`, `error_name`, the originating
application class/method/line (`origin`, or `UNKNOWN`), and exception classes and stack frames without messages or
suppressed exceptions (at most eight causes and forty frames per cause).
Registered application diagnostics retain only enum-defined event/operation/code/name
and sanitized exception details, including inside requests. Other events carrying
a throwable have their original message replaced; within requests, the summary
owns propagated traces. Arbitrary messages and additional key/value pairs are
not rendered for registered diagnostics. Tomcat post-dispatch exception
events omit duplicate stacks. Outside requests, available exception stacks are
sanitized too. Existing handlers' API response messages are not changed by this
logging standard. Review new framework log sources rather than assuming this
converter can sanitize arbitrary plain-text messages without a throwable.

Request context is cleared in `finally`, retaining unrelated MDC keys. This
implementation covers current synchronous servlet endpoints. Async/streaming
endpoints, executor propagation, and scheduled jobs require additional lifecycle
work before they can claim the same correlation/completion guarantees.

## Verification

Run `./mvnw test` and `./mvnw package` from `backend/`. Logging tests exercise
request correlation, cleanup, safe routes, severity, framework message redaction,
and unchanged error responses. Do not use real credentials in fixtures.
