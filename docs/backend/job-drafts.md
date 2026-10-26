# Private Job Posting drafts

Documents GW-SR-JOB-PUBLISHING-MVP private preparation, assignments, preview,
submission, withdrawal, derived corrections and revision history (tickets 01–03 and 05).

Authenticated routes use the current User's Company, never a supplied Company ID.
Owners see all their Company's jobs. Recruiters see only assignments matching their
current active membership ID **and joined-at period**. Company Admin has no job capability.
Inactive membership, suspended accounts and suspended Companies cannot use these routes.
Hidden and absent private job IDs return the same `40478 JOB_NOT_FOUND` response.

| Method | Route | Body / parameters |
| --- | --- | --- |
| POST | `/api/companies/me/jobs` | `{"content": {}}`; returns HTTP and envelope 201 with private detail. |
| GET | `/api/companies/me/jobs` | `page=0`, `size=20` (1–100), optional `status` and `needsReassignment`. |
| GET | `/api/companies/me/jobs/{jobId}` | Positive numeric job ID. |
| PUT | `/api/companies/me/jobs/{jobId}/draft` | `expectedJobVersion`, `expectedRevisionVersion`, `content`. |

Responses retain `ApiResponse` / `PageResponse`. Private detail includes `id`, `companyId`,
`creatorUserId`, `status`, `version`, `ownerResponsible`, `needsReassignment`,
`workingRevision` (`id`, `revisionNumber`, `state`, `version`, `content`),
`publishedRevisionId`, `approvedDeadline`, current `assignments`, `availableActions`,
`createdAt` and `updatedAt`. This slice advertises `SAVE_DRAFT` when editable.
Lists sort by `createdAt DESC, id DESC`. Filters never expand access. Invalid pagination,
including offsets above the integer range, returns 400. Status accepts DRAFT, PUBLISHED,
PAUSED, CLOSED or EXPIRED. Missing versions return 400; stale versions return 409 with
`40022 CONCURRENT_MODIFICATION`. Read current versions from each successful response.
A save replaces the entire content object; omitted fields are removed. Even identical
saves advance both versions. Repeated POSTs create separate jobs: do not blindly retry
an unknown creation result.

Pending and rejected Companies may prepare drafts. Enrolled members must still prove
personal email and phone; enhanced Company membership retains its existing rollout gate.
Legacy drafting does not enroll an account or grant broader employer eligibility.

## Content

POST and PUT bodies are capped at 256 KiB, including chunked requests and ignored
outer fields. Limits are scoped to these content routes. Oversize and malformed content
return 400. Unknown content fields are rejected, including nested compensation/question
fields. Content must be an object, but may be empty or incomplete. Explicit nulls remain
unset; completeness and cross-field submission checks are deferred.

Narrative fields are plain text. Outer whitespace is trimmed; internal Vietnamese Unicode
and line breaks are preserved. Lengths count Unicode code points:

| Fields | Maximum |
| --- | --- |
| `title` | 200 |
| `description`, `responsibilities` | 10,000 each |
| `requiredQualifications`, `preferredQualifications`, `experienceExpectations`, `benefits` | 5,000 each |
| `location` | 1,000 |
| `applicationInstructions` | 2,000 |

`headcount` is an integer 1–10,000. `employmentType` accepts FULL_TIME, PART_TIME,
CONTRACT, INTERNSHIP or TEMPORARY; `workArrangement` accepts ONSITE, HYBRID or REMOTE.
`deadline` requires an ISO-8601 offset and is normalized to the exact UTC instant; a past
proposed deadline may be saved during drafting.

`compensation` accepts `disclosed` (boolean), `minimum`, `maximum` (positive exact decimals,
precision at most 18 and scale at most 2), `currency` (VND/USD) and `payPeriod`
(HOUR/DAY/MONTH/YEAR). Completeness, range ordering, VND whole units and consistency
with disclosure are submission checks.

`screeningQuestions` is an ordered array of at most ten definitions with `key` (unique
canonical UUID), `type` (SHORT_TEXT/SINGLE_CHOICE/YES_NO/NUMERIC), `prompt` (up to 500
code points), `required` (boolean), and optional `options` (at most ten distinct nonblank
labels up to 200 code points each). Incomplete definitions remain drafts; required fields
and option/type consistency are checked at submission. Clients render all text safely.

## Persistence and delivery

The single additive Flyway migration is
`V2026.10.26.83__job_publishing_and_assignments.sql`. It creates Job Postings,
Revisions, Assignments and append-only job audit events, with same-job revision references,
one working revision, approved deadline/pointer consistency and immutable submitted/terminal
history protections for subsequent slices. Assignment history retains its Company and
membership period. Committed migrations and existing data are preserved.

Owner creation enables Owner Responsibility. Recruiter creation assigns their current
membership period and records `JOB_ASSIGNED` intent. Job, initial revision, responsibility,
audit and intent commit atomically. Existing Company transaction locks serialize changes
against membership/assignment revocation; saves also compare both versions.

The existing notification outbox dispatcher delivers after commit, retries with its existing
backoff and deduplicates by source event. Delivery failure does not undo creation. Inbox
responses add nullable `jobId` and `revisionId`; existing membership events remain valid.
The migration also permits the subsequent slices' JOB_UNASSIGNED,
JOB_REVISION_APPROVED, JOB_REVISION_REJECTED and JOB_NEEDS_REASSIGNMENT events.
Historical notification context cannot be rewritten. Active recipients may GET their own
`/api/me/notifications` and PATCH `/api/me/notifications/{id}/read` despite onboarding
restrictions; notification access grants no private job access.

Manual request examples are in [job-drafts.http](../../backend/http/job-drafts.http)
and [job-drafts-errors.http](../../backend/http/job-drafts-errors.http). Use the existing
auth/onboarding/membership request files for setup. Copy returned IDs and both current
versions into the local variables; run optional replacement and negative checks selectively.

Run the existing isolated database setup and `./mvnw test`, then `./mvnw package` from
`backend/`. Focused verification: `./mvnw -Dtest=JobDraftHttpTest,JobPersistenceTest test`.
When iterating on this uncommitted migration, use a fresh disposable test database rather
than changing applied migration history or resetting a development database.

## Preview, submission and review queue

`GET /api/companies/me/jobs/{jobId}/draft/preview` returns `content`, `jobVersion`,
`revisionVersion`, `submissionEligible` and structured `validationIssues` (`field`, `code`).
It reads the exact normalized candidate-facing content without changing either version.

`POST /api/companies/me/jobs/{jobId}/revisions/{revisionId}/submit` requires both
`expectedJobVersion` and `expectedRevisionVersion` plus `confirmed: true`. Submission
requires every narrative and content choice, strictly future deadline, verified Company
and current responsibility. Compensation must explicitly choose disclosure; disclosed
ranges require ordered positive bounds, currency and pay period, with whole VND amounts.
Single-choice questions require 2–10 distinct options; other types prohibit options.
Every question requires a UUID key, type, nonblank prompt and explicit required flag.
Application instructions direct Candidates through GuardWork; human review checks their
meaning rather than unreliable substring detection. Text remains plain text data.

Submission preserves the exact content and transitions only the current draft to
PENDING_REVIEW. Both versions advance. Company/job locking serializes competing submits;
a stale confirmation returns 409 without creating an extra review attempt. Missing
confirmation/versions or incomplete content returns 400. Company verification,
responsibility and deadline conflicts return the scoped 409 feature errors.

`POST .../revisions/{revisionId}/withdraw` requires both versions and a pending revision.
It preserves submission/content attribution, adds withdrawal facts, and releases the
working slot without creating another draft. Closed/expired jobs may still withdraw an
existing pending revision. Every accepted submission/withdrawal records an immutable
audit event in the same transaction. Submitted and terminal history is protected by the
existing PostgreSQL triggers; no new migration is introduced by ticket 03.

Platform Staff members use `GET /api/staff/job-revisions`, with `status` defaulting to
PENDING_REVIEW or APPROVED/REJECTED/WITHDRAWN/ALL, `page=0`, and `size=20` (1–100).
Ordering is `submittedAt ASC, id ASC`. `GET /api/staff/job-revisions/{revisionId}` exposes
exact submitted content, versions, attribution and current approval eligibility.
`GET /api/staff/jobs/{jobId}/revisions` returns submitted history ordered by revision
number. Editable drafts are excluded from every moderation response. All services recheck
current User authority; Company Admin does not grant platform moderation authority.

## Assignment administration

Only the current Owner can `PUT /api/companies/me/jobs/{id}/assignments/{membershipId}`
with `{expectedJobVersion}`, `DELETE` that resource with the required `expectedJobVersion`
query parameter, or `PUT /api/companies/me/jobs/{id}/owner-responsibility` with
`{expectedJobVersion, responsible}`. Targets must be active Recruiters in the same Company.
Owner Responsibility may coexist with multiple assignments. Management authority is independent
of explicit responsibility. Current-version unchanged requests succeed without new audit or inbox events;
stale versions return 409. Assignment and responsibility changes advance the Job Posting version.

Unassignment immediately revokes private access, including creator access. Leaving or removal
ends all assignments in the same transaction, retaining assignment and membership history.
Reactivating a membership never restores an ended assignment. These operations serialize on the
Company lock; membership ending processes jobs in ascending ID order without taking target User locks.
Losing the final responsible person flags the job for reassignment without changing publication state.
Affected Recruiters receive minimal assignment notifications, and the Owner receives a notification
when responsibility becomes absent. Audit and outbox intent commit atomically. Delivery uses the
existing after-commit inbox dispatcher and recovery, so historical notifications remain readable
without granting access to their private job target. This adds no migration or frontend changes.


## Derived drafts and Company revision history

`POST /api/companies/me/jobs/{jobId}/draft` accepts `expectedJobVersion` and optional
`sourceRevisionId`. It creates a distinct DRAFT with the exact same-job source content;
when omitted, the source is the greatest existing revision number. Every new draft uses
the next revision number, records its source/current creator and creation time, advances
the job version and writes `JOB_DRAFT_DERIVED` audit facts atomically. Stable screening
question keys and question order are preserved. Foreign source IDs return the same 404
as missing ones. No historical revision is reset to draft or rewritten.

Creation requires no other DRAFT/PENDING_REVIEW working revision and a nonterminal job.
Company/job locks serialize concurrent derivation; stale versions return 409, and an
existing working slot returns `40979 WORKING_REVISION_EXISTS`. Due approved deadlines
are reconciled before allowing creation. There is no automatic draft after rejection or
withdrawal. A Company Member explicitly derives a new draft to correct submitted content.

`GET /api/companies/me/jobs/{jobId}/revisions` returns the complete currently authorized
history ordered by `revisionNumber ASC`, with normal `page`/`size` bounds. Each entry
includes source, content, creator/time, submitter/time, reviewer/decision/rejection reason
and withdrawal attribution. Owner access and current Recruiter assignments are rechecked
inside the transaction. Platform history continues to exclude editable drafts.

Drafting, saving and submitting a replacement keep the existing Published Revision and
approved deadline unchanged. Rejection/withdrawal also retain them. Approval alone
replaces the pointer/deadline and preserves prior decisions; reconciling the old deadline
first ensures that approval of a future extension never reopens an expired job. Existing
moderation audit and recoverable notification effects apply to each distinct attempt.
No schema migration or frontend work is added by this slice.

Personally verified enrolled members of pending/rejected Companies may use the exact
known Company-job route/method family, including preview/history, assignment administration,
and draft derivation. Submission/resume retain service-level Company verification checks.
Unsupported paths/methods and unrelated employer routes receive no new onboarding bypass.
Focused verification: `./mvnw -Dtest=JobRevisionHttpTest test`.

## Job lifecycle and terminal revisions

Current Owners and assigned Recruiters can `POST /api/companies/me/jobs/{id}/pause`,
`/resume`, or `/close` with `{expectedJobVersion}`. Pause accepts PUBLISHED; close accepts
PUBLISHED or PAUSED. Resume accepts PAUSED only for a verified Company before its existing
approved deadline. Missing versions return 400, stale versions and illegal transitions return
409, and current resource authorization is checked first. Company Admin cannot perform these
operations. Each accepted transition advances the job version and atomically records its audit
fact. Lifecycle actions retain all revision evidence and do not generate a new review decision.

Paused, closed and expired public detail retains approved content with applications unavailable
and the corresponding reason. Paused jobs expire at the exact approved deadline; closed jobs stay
closed. Requests and the existing expiry worker share the same clock and reconciliation logic.
An invalid action rolls back its transaction, including request-side expiry; effective availability
and private list status filters still recognize the deadline, and the worker persists expiry independently.

Closing or expiring a job prevents new drafts, saves, submission and reopening. Existing drafts
remain readable and previewable with terminal eligibility issues. Existing pending revisions remain
withdrawable and reviewable; approval must still pass Company, responsibility and proposed-deadline
checks. Approval replaces approved content while preserving PAUSED, CLOSED or EXPIRED. The previous
approved deadline is reconciled before replacement, so an extension cannot reopen an expired job.
Private `availableActions` reflects current effective state, working revision, Company verification,
responsibility and draft validity. No Application subsystem, frontend or migration is added.

## Integration with `cc8943ab`

Platform moderation uses persisted `STAFF` authority and `/api/staff/...` routes,
matching the baseline role migration and retired `/api/admin/**` contract. Company
`ADMIN` remains a separate role and does not gain moderation rights. Public and
Company job routes retain their existing payloads and authorization boundaries.

The unchanged job migration is ordered after the baseline as
`V2026.10.26.83__job_publishing_and_assignments.sql`.
`JobMigrationCompatibilityTest` creates the baseline schema through `.82`, adds
representative existing Staff and legacy notification data, then migrates normally
and verifies retained data, additive nullable job context, validation and an
idempotent rerun. All 13 baseline migration files remain byte-identical to
`cc8943ab`. Fresh database migration is also covered by normal test startup.
See [ADR 0007](../architecture/decisions/0007-job-integration-migration-and-staff.md)
for the user-approved renumbering and disposable local database rebuild.

Verification on 2026-10-26: `./mvnw test` passed all 272 tests. After adding final
legacy-notification preservation and retired-job-URL assertions, the focused
`JobMigrationCompatibilityTest`, `JobPublicationHttpTest` and
`JobSubmissionHttpTest` run passed 15 tests. `./mvnw -DskipTests package` passed,
reusing the completed test runs. The authorized local `backend_db` rebuild applied
and validated all 14 migrations and restored the five standard demo accounts.
The separate review finding about NUL in moderation rejection reasons remains
outside this migration/Staff integration change.
