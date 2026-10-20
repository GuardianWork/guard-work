# Private Job Posting drafts

Implements GW-SR-JOB-PUBLISHING-MVP ticket 01. This slice provides private preparation;
assignment administration, preview, submission, moderation and publication are later slices.

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
`V2026.10.20.78__job_publishing_and_assignments.sql`. It creates Job Postings,
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
