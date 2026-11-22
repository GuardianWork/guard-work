# GW-119 — Local JD scanning workflow

| Field | Value |
|---|---|
| Specification ID | GW-SR-JD-SCAN-LOCAL |
| Status | Draft; focused implementation scope authorized by the requesting user in this session |
| Owner / human approver | vy (requesting user; branch, schema, service contract and test seams approved in session) |
| Last updated | 2026-11-22 |

This records the local workflow explicitly approved in conversation. Formal lifecycle
approval remains a human action; the agent does not promote this document to Approved.

## Scope and behavior

- Analyze a saved Job Revision, including its full content, without changing job,
  revision, submission or moderation state. PDF/DOCX extraction and frontend are out of scope.
- Every authenticated active account may request and read analysis of a revision it can
  view. Candidates and other public readers may analyze only the published revision under
  job-detail visibility rules. Company Owners and assigned Recruiters retain private job
  access; Staff may analyze submitted revisions, never another user's editable draft.
  Inaccessible resources return the existing job 404.
- POST `/api/companies/me/jobs/{jobId}/revisions/{revisionId}/scans` accepts
  `{expectedRevisionVersion}` and returns the queued or reused scan in the existing
  `ApiResponse` envelope. GET the same path with `/{scanId}` polls that association.
  GET `/api/staff/job-revisions/{revisionId}/scans/{scanId}` is the Staff read route.
- Bind each association to the exact saved revision version. Changed content produces
  a new scan; polling an older association reports `outdated=true`. Submission/state
  changes alone do not make identical analyzed content outdated.
- Reuse scans within the same Company and exact canonical content across all requester
  roles, revisions and jobs. Role, date, mode, profile and corpus changes do not create
  another run for unchanged content. Prefer an existing completed result over unfinished
  or failed historical runs; otherwise reuse active work or the retained failure. New
  content can create a new scan. Failed work is never retried by refreshing. A privileged
  operational retry API is outside this mini workflow.
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

## AI adapter health — session-authorized extension, 2026-11-15

The requesting user (vy) authorized the following additive endpoint and implementation
on the current branch in conversation, explicitly directing that the health check be
public without a Staff role. Formal document lifecycle approval remains a human action.

- GET `/api/ai/health` appears in backend Swagger without a security requirement.
  This exact GET route is public without a login, token or role. Other methods and
  protected endpoints retain existing security rules; no public Staff-route alias exists.
- If scanning is disabled, return 503 with `data.status=DISABLED` without contacting
  the adapter. Otherwise GET the configured loopback adapter's `/health` with a
  two-second HTTP timeout and no retries or redirects.
- Return 200 with `data.status=READY` only when the adapter responds with JSON
  `status=ready` and its mode, profile and legal corpus snapshot match backend
  settings. A matching stub is explicitly reported as `mode=stub`.
- Valid readiness with differing analysis identity returns 503 with
  `data.status=CONFIGURATION_MISMATCH`. Transport errors, timeouts, non-200,
  malformed/incomplete JSON and responses exceeding 4096 bytes return 503 with
  `data.status=UNAVAILABLE`.
- Use the existing `{status,message,data}` envelope and `Cache-Control: no-store`.
  Data allowlists `status`, configured `mode`, `profile`, and `legalSnapshot`.
  Non-ready responses use message `50322 AI_SERVICE_NOT_READY`.
- No model/retrieval calls, analysis jobs, database writes, dependency or schema
  changes. This probes adapter readiness and configuration, not provider access,
  model availability, corpus freshness or answer quality. Credentials, adapter
  URLs, raw upstream responses and exception messages are never returned.

## Configurable service deployment — authorized 2026-11-16

Vy explicitly requested configuring the backend AI service URL and credentials in
`backend/.env`, supporting both local and separately hosted AI services. This
extends the earlier loopback-only client restriction; the default remains local.
The Python adapter may remain bound to loopback behind an authenticated HTTPS
tunnel or gateway.

- The operator-controlled base URL accepts HTTP or HTTPS, an optional port and
  optional path prefix, including localhost, Docker/private service DNS and hosted
  services. Public deployments must use HTTPS; HTTP is for trusted local/private
  networks. Non-loopback HTTP is rejected unless the operator explicitly sets
  `GUARDWORK_AI_ALLOW_INSECURE_HTTP=true` for a trusted private network. Request
  bodies cannot choose or replace this URL.
- Reject non-HTTP(S), relative or malformed URLs, embedded credentials, query
  strings, fragments and invalid ports. Never follow upstream redirects.
- `backend/.env` overrides the optional shared `ai/.env.local` fallback; exported
  process/Render environment variables still take precedence. Configure
  `GUARDWORK_AI_ADAPTER_URL` and `GUARDWORK_AI_SHARED_KEY` for health, JD analysis
  and legal chat.
- The remote service must implement the existing GuardWork adapter request and
  response contracts. Changing location does not accept arbitrary model output,
  disable identity/result validation or create a callback/webhook contract.
- Verify configured client boundaries with synthetic HTTP(S) services and retain
  current authentication, malformed-output, timeout and local workflow regressions.

## Privacy and limits

Only saved job content is sent to the adapter/provider, without account or applicant
data. Reports/snapshots use current access checks on every read. The candidate
visibility and authenticated analysis amendments below permit matching published-content
projections and operations; other snapshots remain subject to private revision access.
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

## Candidate visibility — session-authorized amendment, 2026-11-22

Vy requested candidate access to saved JD analyses and authorized implementation on
the existing branch after the private-report conflict was surfaced. This amendment
supersedes private-only report visibility for published content. Formal specification
lifecycle status remains unchanged.

- `GET /api/jobs/{jobId}` adds nullable `aiAnalysis` under the existing public
  visibility rules, identical for anonymous readers and signed-in Candidates.
- Select the latest completed scan by completion time, then scan ID, associated
  with the exact published revision in the same Company and job, whose canonical
  content hash matches. A draft scan may become visible only when its exact content
  is published. Never substitute a scan of another revision or removed draft text.
- Return `null` if no matching completed scan exists. Queued/failed newer scans
  do not hide a retained completed report. Reads never request/retry scans or
  call the adapter. Missing analysis does not prevent reading or applying to a job.
- Public fields are `mode`, `outdated`, `asOfDate`, `completedAt`, `analysisText`
  and an allowlisted `report` containing schema version, outcome, findings and warnings.
  Finding spans, explanations, severity/verdict and legal citations are retained;
  scan/revision/requester IDs, queue/attempt details, usage and arbitrary adapter
  extension fields are excluded.
- Outdated reports remain readable and explicitly flagged when the Vietnam date,
  profile, mode or legal corpus differs from current configuration. Preserve stub
  labeling, uncertain legal applicability and advisory warnings; no report certifies
  legal compliance. The report still describes exactly the published content.
- Concealed jobs/Companies remain 404, with `Cache-Control: no-store`. Existing
  paused/closed/expired detail visibility continues to apply to the analysis.
- Search lists leave `aiAnalysis=null`; clients fetch the job detail for the report.
  This is an additive API change, with no schema, dependency or scan-creation change.
  Frontend implementation is outside this backend-only checkout.

## Shared authenticated analysis — session-authorized amendment, 2026-11-22

Vy requested shared analysis for all logged-in roles and authorized continuing on the
current branch with the updated behavior after the earlier scope conflict was surfaced.
This amendment supersedes the Company-only request and analysis-identity reuse rules.
Formal specification lifecycle approval remains a human action.

- POST `/api/jobs/{jobId}/revisions/{revisionId}/scans` accepts
  `{expectedRevisionVersion}`. GET the same path with `/latest` requires the
  `expectedRevisionVersion` query parameter. Both use the existing authentication and
  `ApiResponse` envelope, return HTTP 200 and `Cache-Control: no-store`.
- Every operation rechecks active account and current revision visibility. Anonymous
  calls return 401. Inaccessible jobs/revisions return 404 before version/result lookup.
  Missing/negative versions return 400; stale versions return 409. No matching scan on
  GET returns `data: null` and performs no association, queue or provider write.
- Candidates may address only the exact published revision. Staff retain submitted-history
  access; Owner/assigned Recruiter private access remains unchanged. A visible published
  job never grants access to its newer draft or removed draft text. Published paused,
  closed and expired detail visibility remains unchanged.
- `GET /api/jobs/{jobId}` and search projections add `revisionId` and `revisionVersion`
  of the published revision so clients can obtain the concurrency token without access
  to private revision history. The existing public `aiAnalysis` behavior is retained.
- Shared results allowlist `revisionId`, current `revisionVersion`, `status`, `mode`,
  `outdated`, `asOfDate`, `completedAt`, `analysisText`, and `report`. Status is one of
  `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`. Text/report/completion time are null unless
  completed. Reports use the existing public finding/citation allowlist. Requester IDs,
  scan IDs, input snapshots, queue/lease/attempt details, failure/provider internals,
  profile/corpus identifiers, usage and arbitrary extension fields are excluded.
- Matching uses complete canonical content within the Company. Results are projected
  onto the requested current version only after content equality and access checks;
  state-only version changes reuse the scan and POST records the exact version association.
  Date/configuration drift marks results outdated without queueing new analysis.
- The Company row lock serializes content lookup, creation and version association across
  both the new API and existing Company API, including different requester roles/processes.
  No new schema or dependencies are required. Disabling scanning prevents creation while
  allowing authorized reuse and reads. Failed results remain failed without retry.
- Existing Company/Staff scan-ID routes retain their scoped response contracts for client
  compatibility. Shared clients use the new minimal projection; no legacy access is widened.
- Verify all three roles, anonymous denial, inaccessible/private revisions, exact versions,
  mixed-role concurrency, legacy-route reuse, changed-content isolation, all four processing
  states, retained failures, date drift and response privacy against PostgreSQL.
