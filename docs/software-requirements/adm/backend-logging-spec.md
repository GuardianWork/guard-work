# [GW-SR-ADM-01] Backend Operational Logging

| Field | Value |
|---|---|
| Specification ID | GW-SR-ADM-01 |
| Project | GuardWork |
| Module | Backend operational diagnostics |
| Version | 1.1.0 |
| Last Updated | 2026-10-17 |
| Owner | VyTrg |
| Approver | VyTrg (design confirmed in the project conversation, 2026-10-17) |
| Status | Implemented |

## 1. Purpose and scope

Standardize Spring Boot console logging for debugging the existing synchronous
HTTP endpoints, authentication, company registration/resubmission, and company
verification. The project owner explicitly confirmed the design in the logging
interview on 2026-10-17, including the additive header and final consolidated
requirements. The approver name follows the repository Git identity and existing
requirement ownership. Approval evidence is the human's Q2–Q10 answers and final
Q11 confirmation; no agent-assigned Approved lifecycle status is claimed.

Version 1.1 follows the user's explicit Q1–Q6 confirmations in the follow-up
logging interview on 2026-10-17: log-only error codes, registered safe context,
origin discovery, and explicit diagnostic ownership for handled exceptions.
This does not authorize changes to existing API error catalogs or envelopes.

Database audit records remain authoritative for verification decisions under
GW-SR-REC-01 REQ-REC-07 / BR-REC-06. Operational logs do not replace them.
Preserve GW-SR-AUTH-01 NFR-AUTH-04: passwords, password-reset tokens, and refresh
tokens must never appear in logs or audit traces.

## 2. Functional requirements

| ID | Requirement |
|---|---|
| REQ-ADM-01 | Write readable UTF-8 operational logs to the application console, containing timestamp with Vietnam timezone offset, level, logger, and request ID when available. |
| REQ-ADM-02 | Generate a fresh server-owned UUID for each incoming request; do not trust a supplied request ID. Include it in request-scoped logs and return it in `X-Request-ID`. |
| REQ-ADM-03 | Emit one completion summary per synchronous request, including method, route template (or fixed fallback), status, and elapsed milliseconds. Include requests rejected before controller invocation. |
| REQ-ADM-04 | Log normal responses and expected 4xx summaries at INFO; log 5xx summaries at ERROR. Include sanitized exception class and stack frames once at the request boundary when available. |
| REQ-ADM-05 | Enable application DEBUG events in the opt-in local profile, keeping framework/default application logging at INFO. Disable SQL and JDBC bind/extract-value output. |
| REQ-ADM-06 | Add targeted DEBUG event names and outcomes to authentication, registration/resubmission, and verification flows without altering business outcomes or transaction boundaries. |
| REQ-ADM-07 | Remove request logging context on success and failure so pooled threads cannot transfer request IDs between requests. |
| REQ-ADM-08 | Registered application diagnostics retain fixed `event`, `operation`, domain-qualified `error_code`, and `error_name`; arbitrary messages and key/value pairs remain excluded when a throwable is present. Preserve existing feature codes; do not derive HTTP status from a code. Unknown technical failures use `LOG:UNEXPECTED_FAILURE`. |
| REQ-ADM-09 | Exception diagnostics include `origin=<class>.<method>:<line>` from the first application frame of the deepest cause with an application frame. Search toward outer wrappers if necessary; use `UNKNOWN` if no application frame exists. Traverse causes with cycle protection and a finite bound. |
| REQ-ADM-10 | An application diagnostic explicitly logging a caught-and-handled exception retains its sanitized stack even inside a request. Exceptions propagated outward are logged at the request boundary. Callers must not log then rethrow; no automatic exception deduplication is introduced. |

## 3. Privacy and compatibility rules

- Do not log request/response bodies, raw paths, query parameters, request headers,
  cookies, credentials, tokens, IP addresses, user agents, email addresses,
  personal data, DTOs, or entities.
- Use the framework's matched route pattern, never reconstruct one from raw input.
- Omit exception messages and suppressed exceptions; bound cause and frame output.
- Expected client errors do not receive stack traces. Existing MVC exception
  resolvers continue to choose status codes and response bodies.
- Explicit caught-and-handled diagnostics may include sanitized stacks; routine
  expected client errors should continue to use existing DEBUG outcome events.
- Framework console events carrying exceptions redact their original messages.
  Known framework warnings that embed rejected values and development-password
  output must be disabled. Raising framework verbosity requires a privacy review.
- Keep existing dependencies and database schema. No file appender, collector,
  retention service, global error envelope, or authentication changes are included.

## 4. API contract

`X-Request-ID: <server-generated UUID>` is an additive response header on backend
HTTP responses, including errors produced by the application security chain.
It is a diagnostic identifier, never an authentication or authorization input.
Client-supplied values are ignored. Existing bodies and status codes remain
unchanged. No client migration is required. Cross-origin JavaScript exposure is
outside scope; the header is visible in browser network tools.

## 5. Verification and boundaries

Verify request/header correlation, generated ID isolation, MDC cleanup after
success/failure, safe route fallback, severity, privacy with representative
secrets, and preserved security/MVC responses. Run backend tests and packaging.

Current endpoints are synchronous. Async servlet requests, streaming, background
jobs, and propagation into executor threads require a future lifecycle design.
Failures outside a request have no request ID; console exception messages are
redacted and available stack frames are sanitized. Logs before filter invocation
or after a client disconnect may not have a request ID or a deliverable header.

Operational configuration and contributor guidance live in
[`backend-logging.md`](../../convention/backend-logging.md).

Verification on 2026-10-17: all 17 focused logging tests passed. The initial full
test run could not connect to the stopped local PostgreSQL service. After starting
the existing documented Compose service, `./mvnw -o package` passed all 82 tests
and produced the executable JAR. `git diff --check` passed. No dependencies,
migrations, or schema changes were introduced.

Version 1.1 verification on 2026-10-17: the converter regression failed against
the previous implementation because registered context and in-request handled
stacks were missing. After the fix, all 22 focused logging tests passed. A full
`./mvnw -o package` initially failed under the sandbox's JVM agent-attachment
restriction; the authorized run outside the sandbox passed all 87 tests and
built the executable JAR. Standards and Spec reviews found no blocking issues.
The initial feature registry entries are retained as tested examples of existing
catalog identity; they do not migrate existing business error handling.
