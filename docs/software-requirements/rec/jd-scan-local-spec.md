# GW-119 — Local JD scanning workflow

| Field | Value |
|---|---|
| Specification ID | GW-SR-JD-SCAN-LOCAL |
| Status | Draft; focused implementation scope authorized by the requesting user in this session |
| Owner / human approver | vy (requesting user; branch, schema, service contract and test seams approved in session) |
| Last updated | 2026-11-10 |

This records the local workflow explicitly approved in conversation. Formal lifecycle
approval remains a human action; the agent does not promote this document to Approved.

## Scope and behavior

- Analyze a saved Job Revision, including its full content, without changing job,
  revision, submission or moderation state. PDF/DOCX extraction and frontend are out of scope.
- A Company Owner or currently assigned Recruiter can request and read scans using
  the existing job access rules. Platform Staff can read scans of submitted revisions;
  editable drafts remain concealed. Inaccessible resources return the existing job 404.
- POST `/api/companies/me/jobs/{jobId}/revisions/{revisionId}/scans` accepts
  `{expectedRevisionVersion}` and returns the queued or reused scan in the existing
  `ApiResponse` envelope. GET the same path with `/{scanId}` polls that association.
  GET `/api/staff/job-revisions/{revisionId}/scans/{scanId}` is the Staff read route.
- Bind each association to the exact saved revision version. Changed content produces
  a new scan; polling an older association reports `outdated=true`. Submission/state
  changes alone do not make identical analyzed content outdated.
- Reuse scans only within the same Company and exact canonical content, adapter mode,
  analysis profile, corpus snapshot and Vietnam analysis date. Successful, queued and
  running work can be reused; failed work is not reported as successful or retried by
  refreshing. A privileged operational retry API is outside this mini workflow.
- Persist an immutable content snapshot and analysis text, queue state and bounded JSON
  report in PostgreSQL, with separate revision associations and attempt records.
- Claim work under a short database transaction, release locks before HTTP/model calls,
  and finish only with the current unexpired lease token. Recover expired leases;
  retry transient failures with bounded backoff, at most three claims per run.
- Scanning defaults off. The local Python adapter defaults to loopback, requires a
  shared key, and provides explicit `stub` and `crawler` modes. Stub reports never claim
  legal analysis. Real mode requires the sibling crawler environment, Neo4j, provider
  credentials and an explicitly supplied corpus snapshot identifier.
- AI output must be complete, structured and validated. Malformed detection must fail,
  never become an empty successful report. Exact source spans and legal quotes must
  be validated. Legal applicability uncertainty downgrades findings to human review.
- Outcomes are `POTENTIAL_RISKS`, `NEEDS_REVIEW`, `NO_RISKS_DETECTED`, `OUT_OF_SCOPE`;
  processing states are `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`. No output certifies
  legal compliance. Provider outages do not block ordinary job operations.
- No new application dependencies, broker, public Python endpoint, notifications,
  automatic publishing/rejection or edits to `../crawler` are selected.

## Privacy and limits

Only saved job content is sent to the adapter/provider, without account or applicant
data. Reports/snapshots remain private and use current access checks on every read.
Do not log bodies, prompts, provider output or exception messages. Store safe failure
codes and latency; token/cost accounting is explicitly unavailable when the crawler
provider wrapper does not expose usage. Do not invent zero-cost measurements.
Retention follows the existing retained job history for this local prototype; a
production retention/disposal policy and provider assessment remain follow-up work.

## Verification seams

User-approved seams: authenticated scan HTTP API, PostgreSQL-backed queue/reuse,
and the Python adapter contract. Verify exact-version binding, Company isolation,
Staff draft concealment, repeat reuse, stale results, leases/retries and malformed
responses. External providers are substituted only at the HTTP/model boundary.
Run focused tests throughout, the complete backend suite once, packaging, Python
contract tests and a local stub smoke workflow. Real provider calls are optional
and must be reported separately from stub verification.
