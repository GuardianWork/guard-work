# GW-130 — Candidate Access During Job Suspension

| Field | Value |
|---|---|
| Specification ID | GW-SR-CAN-JOB-130 |
| Status | Draft; scoped implementation and requirement amendments explicitly approved by the requesting maintainer in this conversation |
| Owner | GuardWork thesis team |
| Human approver | Requesting maintainer in the GW-130 conversation; display name awaiting confirmation |
| Last updated | 2026-11-24 |
| Scope | Candidate application access and in-app notices for Staff JD suspension/restoration; backend only |

## Approval record and supersession

The maintainer answered “yes to all” to Q2–Q4 and Q5–Q9, then “ues to all”
to Q10–Q13. This explicitly approves the behavior below and amends the Candidate
application concealment rules in application submission, AD-003/AR-001, and
GW128-03's exclusion of Candidate notices. Company Application concealment remains
unchanged. The maintainer instructed implementation on the existing
`feat/GW-130-suspend-jd-from-candidate-site` branch and retained responsibility for
branch synchronization. The document's broader lifecycle status is not set to Approved by an agent.

Later in this conversation the maintainer explicitly revised Candidate access:
Staff suspend whether or not Applications exist; applicants receive a notice; only
otherwise eligible withdrawal remains available on the affected Application.
This supersedes the earlier agreement to retain submitted details/history and CV reads.

## Requirements

| ID | Requirement |
|---|---|
| CAN130-01 | Staff-suspended jobs remain absent from public discovery, including company job listings, and public detail returns the existing non-enumerating 404 without JD content or moderation reasons. New applications and application-form access remain unavailable, including from a previously opened page. |
| CAN130-02 | Candidates retain their own application list rows and matching totals during individual Staff suspension (`status = SUSPENDED`). Search, status filters, order and pagination remain unchanged. Add `jobSuspended: boolean` to Candidate application summaries and details; do not introduce an Application status. |
| CAN130-03 | During individual JD suspension, expose only an owned Application summary/receipt sufficient for eligible withdrawal: IDs, submitted job/company names, Application status/version/time and jobSuspended. Conceal submitted details, history/feedback and CV metadata; canViewCv is false and the Application CV download route returns the existing non-enumerating 404. Independent library CV management is outside the affected-Application restriction. |
| CAN130-04 | Candidates may withdraw during suspension under existing version, terminal-state and idempotency rules. Preserve withdrawal audit/history, CV-grant revocation and existing Company notification behavior. |
| CAN130-05 | Each successful Staff suspension/restoration records a Candidate-audience in-app notice for every eligible existing applicant, including REJECTED, WITHDRAWN, HIRED and INACTIVE. Deduplicate by Candidate per committed event. Resolve recipients inside the business transaction; failed/stale operations record no intent. No historical backfill. |
| CAN130-06 | Use existing JOB_SUSPENDED/JOB_RESTORED event types, JOB category, durable outbox delivery/retry and inbox deduplication. Candidate copy is generic Vietnamese and links to `/applications/{applicationId}`. Include only company/job/application identifiers and company name; exclude candidate contact/CV data and moderation reasons. Existing recruiter recipients, copy and destinations remain unchanged. |
| CAN130-07 | Restoration clears the individual suspension marker without changing Application status. Current publication/deadline/Company rules still govern discovery and new applications. A repeated suspend/restore cycle creates new notices for that cycle. |
| CAN130-08 | Preserve independent `staff_suspended` Company-ban enforcement, account restrictions, recruiter Application concealment and CV lifecycle rules. This change does not authorize access through Company-ban restrictions. Application CV download is blocked during individual JD suspension. Other CV deletion/retention and library rules remain. |

Eligible notification recipients follow current active account access rules (`status` and
`self_service_state` ACTIVE). A recipient is selected from the current Application owner;
no Application status is excluded. Candidates who also receive Company notices receive
separate notices in their respective audiences. A refresh reads current availability;
no push channel, live page invalidation or recall of already delivered content is introduced.

## API, persistence and rollout

Existing envelopes, routes, errors and Application statuses remain. `jobSuspended`
is an additive field, computed from the current individual posting status rather than
the submitted snapshot. Suspended Candidate detail/withdrawal responses use the existing DTO as a minimal receipt; sensitive fields are null, history is empty and canViewCv is false. Existing Company application detail uses the shared DTO and
returns false because suspended postings remain inaccessible to Company readers.
No dependency or new event type is required. Verification found that the existing
notification context constraints reject Application references on JOB_SUSPENDED/JOB_RESTORED.
The maintainer explicitly approved adding and applying this migration by answering
“có” on 2026-11-24. `V2026.11.24.130__candidate_job_suspension_notifications.sql` extends only
`ck_application_outbox_context` and `ck_application_inbox_context` so these two events
require an Application reference for CANDIDATE and preserve the existing RECRUITER shape.
There is no row rewrite. Apply the migration through Flyway before running writers
that produce Candidate job notices. Migration files are copied into the runtime classpath
by the build; Flyway runs at application-context startup, including an actual DevTools
restart, rather than directly watching SQL source-file saves. Deploy compatible backend notification readers/writers together so Candidate
job notices resolve to the Candidate destination. Frontends should display
“Tin tuyển dụng đang bị tạm ngưng” separately from the Application status and
use the Application destination even while public JD detail returns 404.

No Candidate frontend or saved-job feature exists in this repository. Those implementations
are deferred; this scope delivers backend behavior, tests and integration documentation.

## Verification

Exercise real Staff hide/restore routes with status-only suspension; Candidate rows/counts,
search/filter/paging, minimal receipts, ownership, concealed history/CV metadata, blocked Application CV download and eligible withdrawal;
public concealment/new-application denial; independent Company-ban restrictions;
all Application-status notice recipients, audience/destination separation, stale retries,
delivery deduplication, rollback on Candidate-intent failure and repeated cycles.
Run focused PostgreSQL HTTP tests, compilation and the documented full backend test suite.
