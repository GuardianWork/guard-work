# GW-93 — Staff Job Hiding and Posting History

| Field | Value |
|---|---|
| Specification ID | GW-SR-ADM-JOB-93 |
| Status | Approved by the requesting maintainer in the GW-93 conversation |
| Owner | GuardWork thesis team |
| Human approver | Requesting project maintainer; display name awaiting confirmation |
| Last updated | 2026-10-31 |
| Scope | Backend UC-13 / UC-ADM-04-H and UC-14 / UC-ADM-04-L; ADM-JOB-01–08 |

## Approval record

The requesting maintainer explicitly answered `yes` on 2026-10-31 to the linked implementation contract and approval request. This approves this focused extension of the approved GW-78 job lifecycle and its additive database migration. The Staff source specification remains Draft for all other capabilities. Approval does not include UC-21 restoration, frontend implementation, new dependencies, notification delivery, or search/cache infrastructure.

## UC-13: Hide a public Job Posting

- Add `PUT /api/staff/jobs/{jobId}/hide` with JSON `{ "expectedJobVersion": 0, "reason": "Policy violation details" }`.
- Require an authenticated currently active persisted Platform `STAFF`; Company membership grants no Staff authority. Recheck at the service boundary.
- Require a nonnegative current job version and a reason of 1–1000 Unicode code points after stripping surrounding whitespace. Missing/invalid values return the existing 400 validation envelope. Stale versions return 409 `40022 CONCURRENT_MODIFICATION`.
- Lock actor User, Company, then Job Posting, following existing job moderation lock order. Refresh the job after locking. Require an effectively `PUBLISHED` job with approved content, a future approved deadline, and a verified non-suspended Company. An unavailable/non-public current state returns 409 `40978 JOB_STATE_CONFLICT`; a missing job returns 404 `40478 JOB_NOT_FOUND`.
- Atomically change `PUBLISHED` to `SUSPENDED`, advance the optimistic version/update time, append `JOB_SUSPENDED` to job history and record the existing platform audit entry. Retain actor, time, trimmed reason, previous/new status and resulting job version. Audit failure rolls back the entire mutation. Retrying a stale request cannot create another successful event.
- Return HTTP 200 in the existing `ApiResponse`, with job ID, publication status, job version, actor ID, suspension time and reason.
- Preserve all content, revisions, assignments and prior events. Existing authorized Company reads remain possible; support the `SUSPENDED` Company list filter. Suspended postings reject draft derivation/edit/submission and pause/resume/close. Existing pending revisions remain inspectable and withdrawable; Staff may approve/reject them under existing safeguards, but approval preserves `SUSPENDED` and never restores visibility. Assignment/responsibility management retains existing authorization.
- Public `GET /api/jobs/{jobId}` returns the existing non-enumerating 404 for suspended jobs without content. Send `Cache-Control: no-store` for public job detail successes/errors so compliant clients do not retain a public representation across future hides. Already delivered content cannot be recalled. Existing cached responses from before this rollout may need operational invalidation.
- No search/listing or server content-cache integration exists in the inspected backend. Verify all existing public read paths; document this boundary explicitly. Future search/cache integrations must exclude suspended jobs and define invalidation before rollout.
- Scheduled expiry does not replace `SUSPENDED` or restore visibility. UC-21 restoration remains a separate approval.

## UC-14: Read posting events

- Add `GET /api/staff/jobs/{jobId}/history?page=0&size=20`; this is event history, separate from the existing `/revisions` endpoint.
- Require currently active persisted Platform `STAFF`, including at the service boundary. Permit history for any existing job, including suspended and unpublished jobs; Company membership is not required.
- Reuse `PageResponse`, page >= 0, size 1–100, safe offset bounds. Sort by `createdAt DESC, id DESC` for deterministic newest-first results.
- Project recorded append-only `job_audit_events`: event ID, job ID, nullable revision/membership references, nullable actor User ID, event type, timestamp, previous/status/reason where actually recorded. System expiry actor is null. Do not fabricate missing historical status/reason or backfill old events.
- Return no arbitrary audit JSON, private contacts, credentials, applicant data or full job content. The job ID supplies return-navigation context.
- Existing job without events returns HTTP 200 with an empty page. Missing job returns 404; invalid paging 400; unauthorized access 401/403; retrieval failure uses the existing error envelope and never masquerades as an empty success.
- Reading does not reconcile expiry, append events or mutate posting/content state. Preserve current append-only database guards.

## Persistence and compatibility

- Add a new Flyway migration `V2026.10.31.86__staff_job_suspension.sql` extending only the job status check to include `SUSPENDED`. Do not edit committed migrations.
- Reuse existing job event JSON facts and platform audit reason storage; no new suspension columns, tables or dependencies.
- Existing clients consuming private job status must support additive `SUSPENDED`. Deploy compatible clients/backend together; older backend writers must not run against suspended jobs. Rollback requires reviewed handling of suspended rows, not silently mapping to PAUSED/PUBLISHED.
- Record the scoped requirement extension, migration and runtime behavior in authoritative project docs and a lightweight ADR. Do not mark unrelated Staff requirements Approved.

## Verification

Use existing PostgreSQL test infrastructure and controlled Clock. Test HTTP and direct-service authority, suspended User/Company behavior, required reason bounds and Unicode, missing/stale version, every invalid source state including deadline equality, atomic job/platform auditing and rollback, public detail hiding and cache headers, preserved content/revisions, Company inability to restore visibility, pending revision decisions preserving suspension, deterministic paginated event history, nullable system actor, empty versus failed history, and read-only history inspection. Test migration on retained job data and immutable audit guards. Run focused test files during implementation, Maven compilation for Java typechecking, then `./mvnw test` and `./mvnw package` at completion. Review with the requested code-review skill before committing; remote push/PR is outside the current authorization.

## Ticket source limitation

GW-93 was supplied as a branch/ticket identifier, but no Jira connector, issue URL or ticket body was available. Its issue-tracker title has not been verified. The supplied UC references and repository Staff documents are the current product inputs.
