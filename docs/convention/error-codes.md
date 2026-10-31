# Error codes in operational logs

Scope: [GW-SR-ADM-01](../software-requirements/adm/backend-logging-spec.md),
version 1.1, confirmed by the project owner in Q1–Q6 on 2026-10-17.

## Existing contracts

Feature requirements own their error catalogs:

- [Authentication, section 7](../software-requirements/auth/authentication-authorization-spec.md#7-error-catalogue)
- [Company verification, section 7](../software-requirements/rec/company-verification-spec.md#7-error-catalogue)

The taxonomy in `app-convention.md` is a reusable template, not a replacement for
those feature contracts. There is currently no central `AppException`,
`ErrorCode`, or global error-response handler. This convention establishes log
identifiers only; it does not introduce those components or change API payloads,
HTTP status codes, exception reason strings, or existing feature numbers.

## Identity and registration

Write `error_code=<DOMAIN>:<existing-code>` and a fixed uppercase `error_name`.
Numeric codes are unique within a domain, not across the project. For example:

| Log code | Error name | Source |
|---|---|---|
| `AUTH:40101` | `INVALID_CREDENTIALS` | Authentication catalog |
| `AUTH:40901` | `USERNAME_OR_EMAIL_EXISTS` | Authentication catalog |
| `COMPANY:40901` | `DUPLICATE_TAX_CODE` | Company verification catalog |
| `LOG:UNEXPECTED_FAILURE` | `UNEXPECTED_FAILURE` | Technical fallback defined here |

Never infer HTTP status from the numeric prefix: company code `40022`
(`CONCURRENT_MODIFICATION`) has HTTP status 409 in its existing requirement.
Do not renumber feature errors to remove overlaps without human approval.

Register application diagnostic events in
`com.guardwork.backend.logging.DiagnosticEvent`. Each constant fixes the event,
operation, qualified code, and error name. The initial AUTH and COMPANY entries
pin representative existing catalog identities, including the two meanings of
`40901`, and are covered by regression tests. They are not automatically emitted
by existing business flows. Add further entries when a concrete call site needs
them; reuse the approved feature catalog and review literals for privacy.
Use lowercase snake_case for event/operation and uppercase snake_case for error
names. New domains/codes need documented meaning and approval under the root guide.

Request-boundary 5xx summaries use `LOG:UNEXPECTED_FAILURE` as the technical
fallback. The filter does not parse exception messages/reasons or guess a domain
from a logger, URL, or stack frame. It does not automatically translate every
existing business exception into a registered diagnostic.

## Origin and exception ownership

Every rendered exception stack includes `origin=<class>.<method>:<line>`.
For each cause, inspect its first `com.guardwork.backend.` frame; prefer the
deepest cause with an application frame, falling back toward outer wrappers.
This identifies the throw location rather than the logger caller, regardless of
controller/service/repository layer. Missing application frames yield `UNKNOWN`;
missing debug line metadata can yield a negative line number. Cause traversal is
cycle-safe and limited to eight exceptions; stack output is limited to forty
frames per exception. Suppressed exceptions and exception messages are excluded.

For a failure caught and handled locally, use:

```java
catch (RuntimeException failure) {
    DiagnosticLog.error(log, DiagnosticEvent.UNEXPECTED_FAILURE, failure);
    // Continue with the existing fallback behavior.
}
```

The helper uses SLF4J structured metadata containing the enum itself. The console
converter accepts it only for application loggers. It renders only registered
constants and sanitized exception details, ignoring arbitrary formatted messages
and other structured fields. String values cannot impersonate registered events.
Use this helper with class loggers under `com.guardwork.backend.`.

For failures propagated outward, do not log then rethrow: the HTTP boundary owns
the stack. A handled failure may have an ERROR diagnostic followed by a successful
INFO request summary; these describe different events. No automatic exception
deduplication is performed. Routine expected authentication/validation failures
continue to use existing DEBUG outcomes and INFO request summaries.

Ordinary `log.error("event=...", failure)` remains redacted. Do not pass dynamic
data or raw messages through a different overload to bypass the registry.
Framework throwable messages remain redacted. The converter does not sanitize
arbitrary text logs that have no attached throwable.

## GW-95 scoped account-control additions

These scoped ResponseStatusException codes preserve the existing envelope; they do not select a new global error architecture.

| Code | HTTP | Meaning |
|---|---|---|
| 40094 | 400 | INVALID_ACCOUNT_ACTION_PROOF — invalid, expired, consumed or exhausted lifecycle code |
| 40994 | 409 | COMPANY_OWNERSHIP_EXISTS — resolve Company ownership before closure |
| 40995 | 409 | ACCOUNT_LIFECYCLE_CONFLICT — action does not match current voluntary lifecycle/request |
| 42994 | 429 | ACCOUNT_ACTION_COOLDOWN — Retry-After seconds before another code request |
