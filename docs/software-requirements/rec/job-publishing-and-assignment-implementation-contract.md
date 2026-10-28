# GW-78 — Job Publishing and Assignment Implementation Contract

| Field | Value |
|---|---|
| Specification | [GW-SR-JOB-PUBLISHING-MVP](job-publishing-and-assignment-mvp-spec.md) |
| Status | Approved |
| Owner / named human approver | Thinh (retained from baseline) |
| Date / Last Updated | 2026-10-28 |
| Base revision | `50316c8` |

## 1. API conventions and access

Use existing `ApiResponse<T>` and `PageResponse<T>`, authenticated UserPrincipal and live active-User checks. IDs are positive BIGINT/JSON numbers consistent with Company/Membership APIs. Private Company is always derived from the actor, not client-supplied authority. All private job identifiers are scoped before returning content; inaccessible identifiers return the same 404 as absent ones.

Add only GET `/api/jobs/{jobId}` as anonymous public access, with positive numeric ID parsing. No wildcard anonymous job writes or search endpoint. Platform routes require current platform STAFF authority; Company role ADMIN grants nothing. A Staff member's membership of the reviewed Company is not a reason to refuse a moderation operation.

Lists default to `page=0&size=20`, size 1–100, page nonnegative within safe offset bounds. Company jobs sort `createdAt DESC, id DESC`; reviews sort `submittedAt ASC, id ASC`; revision history sorts revision number ascending. Paginated responses retain the existing field names.

## 2. Proposed routes and payloads

Every mutation of an existing job includes `expectedJobVersion`. Draft/review mutations additionally include `expectedRevisionVersion`. Successful mutations return current versions. Missing versions are 400; stale versions are 409; unauthorized resource scope is checked first. Versions advance for lifecycle, content, assignment, responsibility and review changes. Creation returns HTTP/envelope 201; other successful operations use 200 with the existing envelope.

| Method and route | Request / response and behavior |
|---|---|
| POST `/api/companies/me/jobs` | `{content: {...}}`, content may be empty; creates job plus initial draft and automatic responsibility/assignment atomically. Returns private job detail. A repeated create is a separate job; client must not blindly retry an unknown creation result. |
| GET `/api/companies/me/jobs` | Optional `status=DRAFT/PUBLISHED/PAUSED/CLOSED/EXPIRED`, `needsReassignment=true/false`; Owner sees Company jobs, Recruiter sees only their currently assigned jobs. No filter broadens authority. |
| GET `/api/companies/me/jobs/{jobId}` | Private detail: job state/versions, Owner Responsibility, active assignments, reassignment flag, working and Published Revision references, approved deadline and available actions. |
| GET `/api/companies/me/jobs/{jobId}/revisions` | Paginated history including reviewed content and feedback for currently authorized members. |
| POST `/api/companies/me/jobs/{jobId}/draft` | `{expectedJobVersion, sourceRevisionId}`; source must belong to this job. Derives draft from latest revision if omitted; reject when another working revision exists or job is terminal. |
| PUT `/api/companies/me/jobs/{jobId}/draft` | `{expectedJobVersion, expectedRevisionVersion, content}`; replaces complete draft content, omitted fields become unset. Incomplete content allowed within structural bounds. |
| GET `/api/companies/me/jobs/{jobId}/draft/preview` | Returns candidate-facing content projection, both versions, `submissionEligible`, and `validationIssues: [{field, code}]`; no submission side effect. |
| POST `/api/companies/me/jobs/{jobId}/revisions/{revisionId}/submit` | `{expectedJobVersion, expectedRevisionVersion, confirmed: true}`; revision must be current draft, exact version, fully valid and eligible. |
| POST `/api/companies/me/jobs/{jobId}/revisions/{revisionId}/withdraw` | Both versions; pending only. Preserves submitted content; ends working slot, without automatically creating another draft. |
| PUT `/api/companies/me/jobs/{jobId}/assignments/{membershipId}` | `{expectedJobVersion}`; Owner-only, active same-Company Recruiter. Existing active assignment with current version returns unchanged success and no duplicate event. |
| DELETE `/api/companies/me/jobs/{jobId}/assignments/{membershipId}` | Required `expectedJobVersion` query parameter; Owner-only, ends current assignment period. Already-ended assignment with current version is unchanged success; preserve history. |
| PUT `/api/companies/me/jobs/{jobId}/owner-responsibility` | `{expectedJobVersion, responsible: boolean}`; Owner-only. Repeating current value creates no event. |
| POST `/api/companies/me/jobs/{jobId}/pause` | Job version; PUBLISHED only after expiry reconciliation. |
| POST `/api/companies/me/jobs/{jobId}/resume` | Job version; PAUSED only, approved deadline future and Company verified. |
| POST `/api/companies/me/jobs/{jobId}/close` | Job version; PUBLISHED/PAUSED only; do not withdraw pending revision. |
| GET `/api/staff/job-revisions` | Paginated filter `status=PENDING_REVIEW/APPROVED/REJECTED/WITHDRAWN/ALL`, default pending; never list private editable drafts as review requests. |
| GET `/api/staff/job-revisions/{revisionId}` | Exact submitted content, current Company/job eligibility and versions, decision/withdrawal metadata. |
| GET `/api/staff/jobs/{jobId}/revisions` | Submitted revision history including preserved rejected/withdrawn content; editable drafts excluded. |
| PUT `/api/staff/job-revisions/{revisionId}/decision` | `{expectedJobVersion, expectedRevisionVersion, outcome: APPROVED|REJECTED, reason?}`; pending only; rejection needs reason. Return decision receipt and resulting job state/versions. |
| GET `/api/jobs/{jobId}` | Public approved content, `{company: {id, name}, publicationStatus, applicationsAvailable, applicationAvailabilityReason}`. No private identifiers other than public job/Company IDs. |

`applicationsAvailable` is false for PAUSED/CLOSED/EXPIRED or Company not verified, true only for verified non-suspended PUBLISHED before approved deadline. It describes job eligibility, not an implemented Application submission endpoint. Reasons: `PAUSED`, `CLOSED`, `EXPIRED`, `COMPANY_NOT_VERIFIED`, or null. Suspended Company returns 404 instead of a public dossier/status disclosure.

All narrative strings are data, never HTML to execute. `applicationInstructions` is plain text directing in-platform submission; no separate external application URL field is provided. Clients must render text safely. Human moderation rejects instructions that require applying outside GuardWork; substring detection is not a substitute for review.

## 3. Proposed content object and bounds

Lengths count Unicode code points after outer whitespace trimming, preserving internal text and line breaks. Draft fields may be absent/null/empty. Structural bounds, enum validity and nonnegative supplied numbers apply even in drafts; completeness, cross-field requirements and future deadline apply at submission and approval. Reject unknown content fields to catch client mistakes. No arbitrary HTML-to-text conversion.

| JSON field | Submission requirement and proposed bounds |
|---|---|
| `title` | Required, 1–200 characters. |
| `description`, `responsibilities` | Each required, 1–10,000 characters. |
| `requiredQualifications`, `preferredQualifications` | Each required, 1–5,000 characters; explicit text such as “None” is permitted where genuinely applicable. |
| `experienceExpectations`, `benefits` | Each required, 1–5,000 characters. |
| `applicationInstructions` | Required, 1–2,000 characters. |
| `employmentType` | FULL_TIME, PART_TIME, CONTRACT, INTERNSHIP or TEMPORARY. |
| `workArrangement` | ONSITE, HYBRID or REMOTE. |
| `location` | Required plain text, 1–1,000 characters; for remote work describe eligible location/time zone rather than an invented street address. |
| `deadline` | ISO-8601 datetime with explicit offset, mapped to TIMESTAMPTZ; strictly future at submission/approval. No ambiguous local datetime or default midnight. |
| `headcount` | Integer 1–10,000. |
| `compensation` | `{disclosed: boolean, minimum?, maximum?, currency?, payPeriod?}`. Required explicit choice. If disclosed, positive decimals (precision 18, scale ≤2), min ≤ max, currency VND/USD, period HOUR/DAY/MONTH/YEAR. VND amounts must be whole numbers. If not disclosed, remaining fields must be null/absent. Zero/negative placeholders rejected; misleading nonzero claims remain moderation concerns. |
| `screeningQuestions` | Ordered array of 0–10 objects `{key, type, prompt, required, options?}`. Unique stable UUID key per question; prompt 1–500 characters. |
| `screeningQuestions[].options` | SINGLE_CHOICE only, 2–10 distinct nonblank labels of 1–200 characters each. Other question types must have no options. Choice selection is single, not multiple. |

Question keys persist when deriving unchanged questions; changed/new questions may use new keys. A future Application snapshot must identify the exact revision/question definitions used. Numeric/short-text answers and their constraints are not implemented in GW-78. Proposed rejection reason: 10–1,000 characters after trimming. Bound JSON request size to 256 KiB for job content routes without changing unrelated upload limits.

## 4. Proposed database changes

One new Flyway migration `V2026.10.26.83__job_publishing_and_assignments.sql`, ordered after the `cc8943ab` baseline's `.81` and `.82` migrations. Disposable local databases with an earlier migration version require rebuilding. Retain existing shared data and committed migration history. See [ADR 0007](../../architecture/decisions/0007-job-integration-migration-and-staff.md).

| Storage | Required fields and constraints |
|---|---|
| `job_postings` | BIGSERIAL id, Company FK, creator User FK, status enum, Owner-responsible boolean, nullable Published Revision FK, approved deadline instant, optimistic version, created/updated timestamps, nullable closed/expired timestamps. Approved revision/deadline must appear together; non-DRAFT states require approved content. CLOSED/EXPIRED cannot transition back in GW-78. |
| `job_posting_revisions` | BIGSERIAL id, job FK, monotonic revision number unique per job, nullable source revision FK constrained to same job, content JSONB, state, creator/submitter/reviewer/withdrawer User references, submission/decision/withdrawal times, rejection reason, version. Partial unique index permits at most one DRAFT/PENDING_REVIEW per job. Composite FK ensures Published Revision belongs to its Job Posting; approval must be checked atomically before publication pointer changes. |
| `job_assignments` | BIGSERIAL id, job and Company-scoped membership FKs, membership's joined-at snapshot, assigning User, assigned time, nullable ended time/actor/reason. Partial unique index on job/membership for unended assignments. Validate same Company and active RECRUITER period under lock; a later reactivation cannot revive ended rows. Membership departure/removal ends these rows in the same transaction. |
| `job_audit_events` | Append-only BIGSERIAL id, Company/job FKs, nullable revision/membership references, nullable actor User for system expiry only, event type, timestamp and minimal structured old/new facts. Protect UPDATE/DELETE/TRUNCATE using the existing append-only audit pattern. Record platform decisions through existing Admin audit as well; no need to duplicate full content snapshots in audit payloads. |
| Existing notification tables | Add nullable job/revision FKs and minimal immutable event context to outbox/inbox. Extend existing event-type checks for JOB_ASSIGNED, JOB_UNASSIGNED, JOB_REVISION_APPROVED, JOB_REVISION_REJECTED and JOB_NEEDS_REASSIGNMENT. Preserve legacy row validity and existing inbox response fields. Add optional `jobId`/`revisionId` context; no content body or rejection reason in minimal historical notifications. |

Submitted revision content, creator/source/submission facts cannot mutate after DRAFT. State may only complete once from PENDING_REVIEW to APPROVED/REJECTED/WITHDRAWN with consistent actor/time metadata. Terminal revision rows cannot mutate or be deleted. A PostgreSQL trigger protects these invariants; no hard-delete endpoint for jobs, assignments, reviews or audit.

Index Company job queues by `(company_id, created_at DESC, id DESC)`; revisions by job/revision number and pending `(submitted_at,id)`; assignments by membership and job where unended; live jobs by approved deadline; audit by job/time. Keep JSONB content opaque to query filters; private summaries read title from current working content or approved content without public leakage. No search index, Candidate tables, Redis or message broker.

## 5. Transaction, lifecycle and expiry contract

- Business services own transactions. Follow established lock order: actor User → Company → actor membership → Job Posting → target membership/assignment/revision. A cross-Company operation never acquires a foreign job lock first. Membership ending already owns Company/membership locks, then ends affected jobs/assignments in ascending job-ID order. Do not lock target Users after Company; read target eligibility in a manner compatible with the existing active-User update paths and test races before declaring complete.
- Current Company state, membership period, actor eligibility and job responsibility are checked under the transaction's locks. Company locking serializes job operations against membership changes and verification decisions. Worker expiry locks Company before jobs in the same order. Platform decisions lock their active User then Company/job; no requirement for Company membership.
- Keep an injected Clock. Reconcile PUBLISHED/PAUSED deadline expiry before acting; worker scans due jobs every proposed 60 seconds in bounded batches of 100. Both worker and request reconciliation are idempotent and record exactly one expiry. Do not claim a database mutation persisted if a later failed action rolls its transaction back: public availability still computes the effective terminal state, and scheduled reconciliation persists it independently.
- Approval first reconciles the previous approved deadline. If expiry occurred, persist EXPIRED when approval commits, update approved content and retain EXPIRED. First approval uses the new deadline and publishes DRAFT. Paused/closed state is preserved. Future deadline alone is never an instruction to reopen.
- Notify each user once per committed event. Repeated unchanged assignment/responsibility does not create another event. A retried stale decision fails with 409, and clients reload the current receipt/history rather than resubmitting blindly.
- Notification intent uses the business transaction; dispatch/retry remains after commit. Event-time recipients are preserved for delivery; opening a target always uses current access. Membership-ended job notifications carry no confidential content and do not replace existing membership notifications.

## 6. Recruiting access integration

Recruiting access uses current account and Company state at the HTTP and service boundaries:

1. Recognize the exact Company job route family separately: require active membership, eligible User and non-suspended Company; require verified account email for every User, without phone proof or automatic employer-profile enrollment. Allow authorized draft preparation/management for PENDING/REJECTED Companies; submit/resume and moderation approval separately require VERIFIED. Do not broaden other employer routes.
2. Permit exact public GET `/api/jobs/{numericId}` for signed-in restricted applicants as well as anonymous readers. Public projection remains identical and grants no private Company access.
3. Permit existing own-inbox GET `/api/me/notifications` and PATCH `/api/me/notifications/{numericId}/read` while onboarding is restricted, subject to active-User/recipient checks.
4. Do not bypass Company/job service checks because an interceptor allowed a route; recheck eligibility within business transactions. Preserve normal Platform Staff moderation, including own-Company reviews.

These rules preserve route-specific permissions and recheck current eligibility in business transactions.

## 7. Errors and verification

Use the existing scoped `ApiResponse` HTTP/error convention; validation fields can be identified by stable paths in the message while retaining data=null. Preview has structured validation issues for form clients. Do not introduce a new global error envelope.

| HTTP | Semantic result |
|---|---|
| 400 | Malformed payload, missing confirmation/version, invalid field/type, incomplete submission or invalid paging. |
| 401 | Existing unauthenticated/invalid-token behavior. |
| 403 | Current User suspended, wrong non-resource role, or authenticated caller lacks Platform Admin authority. |
| 404 | Missing or inaccessible private job/revision/membership; public job not visible. |
| 409 | Stale version, working revision already exists, not pending, Company not verified, missing responsible person, passed deadline or illegal lifecycle transition. |
| 500 | Generic unexpected failure with request correlation; no raw constraint names, content or private metadata. |

Retain existing `40000 BAD_REQUEST`, `40022 CONCURRENT_MODIFICATION`, `40100 UNAUTHORIZED`, `40304 ACCOUNT_SUSPENDED` where applicable. Proposed feature codes are `40478 JOB_NOT_FOUND`, `40978 JOB_STATE_CONFLICT`, `40979 WORKING_REVISION_EXISTS`, `40980 JOB_RESPONSIBILITY_REQUIRED`, `40981 JOB_DEADLINE_PASSED`, `40982 COMPANY_VERIFICATION_REQUIRED`. Verify no collision before use. Handle new feature exceptions before catch-all advice; do not change unrelated features' errors.

Required HTTP/security/PostgreSQL coverage:

- Owner and Recruiter draft creation; automatic responsibility/assignment; partial failure rolls back all effects; Unicode and malformed/oversized content.
- Verified and pending/rejected Companies; personal-onboarding independence and unrelated-route restrictions; suspended User/Company; Company Admin denied; cross-Company hidden IDs.
- One working revision and stale save/submission races; preview version/confirmation; immutable reviewed snapshots, correction, withdrawal versus decision and concurrent decisions.
- Owner-only assignment/responsibility; duplicate retries; creator unassignment; membership removal/leave/rejoin; no resurrection; last-responsible flag and exactly-once notification.
- Admin own-Company decisions permitted; non-Admin denied; first approval and replacement visibility; pending content never leaks.
- Pause/resume/close/expiry transition matrix; paused expiry; approved versus pending deadline; old deadline crosses during approval; retained pending reviews after closure/expiry; approving future content never reopens.
- Anonymous and restricted signed-in public detail; approved-only projection; suspended Company hidden; no private Company data; ordinary public GET is sufficient without server frontend changes.
- Transaction rollback of audit/outbox on failed business operation; dispatch failure/recovery, recipient deduplication, historical notification access without restored job access.
- New migration against existing schema/data; database immutable-history and unique-working-revision constraints; legacy notification compatibility.

Reuse existing isolated PostgreSQL test setup and controlled Clock/delivery boundaries. Run `./mvnw test` and `./mvnw package` from backend after implementation. No new testing dependency or destructive database operation is proposed. This documentation change itself requires link/consistency checks, not a backend test run.

## Implementation and verification

The backend provides Company job APIs, Platform Staff moderation at `/api/staff/...`, public approved detail, and expiry reconciliation. Moderation reads current persisted `STAFF` authority; Company `ADMIN` membership grants no moderation authority. Migration 83 follows the `.81` and `.82` baseline migrations; see [ADR 0007](../../architecture/decisions/0007-job-integration-migration-and-staff.md).

Private recruiting operations require verified account email for every User under [GW-SR-AUTH-03](../auth/unified-registration-implementation-contract.md). Phone proof and historical employer profiles do not determine eligibility. See the [workflow guide](../../backend/job-drafts.md) and backend README for routes, expiry configuration, and test/package commands.
