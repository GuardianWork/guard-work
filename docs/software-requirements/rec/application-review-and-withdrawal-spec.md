# Application Review and Withdrawal — Software Requirements Specification

| Field | Value |
|---|---|
| Specification ID | GW-SR-APPLICATION-REVIEW |
| Project | GuardWork |
| Owner | Thinh |
| Approver | Thinh |
| Status | Draft metadata retained under repository policy; explicit human approval recorded below |
| Version | 0.1.0 |
| Date | 2026-11-03 |
| Last Updated | 2026-11-03 |

## Scope and approval record

On 2026-11-03, Thinh answered “yes” to Q16, confirming shared understanding and approving implementation of this document, including its concrete HTTP contracts and validation. Thinh subsequently approved the five-ticket breakdown. The agent has not set lifecycle metadata to Approved.

Backend review stages, rejection, Candidate withdrawal, immutable history, and in-app notifications. Thinh approved clarification questions Q4–Q15 on 2026-11-03, including additive schema changes for statuses, history/rejection data and notification application context; existing snapshots remain unchanged. Work stays on the current branch and preserves unrelated changes. No dependency, commit, push, or PR is authorized.

This extends [Application Listing](application-listing-spec.md) and coordinates with [Application Details](application-details-and-cv-spec.md). The broader draft pipeline uses APPLIED and permits Admin; this scoped release preserves SUBMITTED and excludes Admin. Interviews, Offers, Hiring Confirmation, backward corrections, reopening, reapplication, bulk actions and separate Internal Notes are deferred.

## State model

```text
SUBMITTED -> UNDER_REVIEW -> SHORTLISTED
     +-----------------------> SHORTLISTED
Each active status -> REJECTED or WITHDRAWN
```

REJECTED and WITHDRAWN are terminal. Company actors cannot withdraw on behalf of Candidates. Opening details does not change status. Job closure or expiry does not change application status or block otherwise authorized processing.

## Functional requirements

| ID | Requirement |
|---|---|
| AR-001 | Owner and current assigned Recruiter actions shall use the same live access checks as listing and details, including suspended-job denial and Admin exclusion. |
| AR-002 | Authorized Company actors may move forward through active stages or reject from any active stage. Candidates may withdraw their own active Applications. |
| AR-003 | Every mutation shall require the current Application version. Stale versions shall return 409 without side effects. Authorization shall precede disclosure of version or state. |
| AR-004 | With a current version, a request for the already-current status shall succeed without another event or notification. Repeated rejection shall not modify its original reason or feedback. Stale retries still return 409. |
| AR-005 | Status, append-only history and durable notification intents shall commit atomically. Each history entry records source, destination, actor and time. Concurrent decisions shall not silently overwrite one another. |
| AR-006 | Rejection requires QUALIFICATIONS_MISMATCH, EXPERIENCE_MISMATCH, POSITION_FILLED or OTHER. OTHER requires an internal explanation. Candidate-facing feedback is optional and separate. Internal reasons and explanations shall never appear in Candidate responses or notifications. |
| AR-007 | Candidates shall see current status, transition times and explicit Candidate-facing feedback. Authorized Company readers shall see history attribution and internal rejection reasons. |
| AR-008 | Candidates shall receive in-app notifications for active-stage changes and rejection. Rejection messaging shall be neutral and may include explicitly supplied Candidate-facing feedback. Withdrawal shall notify the current Owner and currently assigned Recruiters, deduplicated by recipient. |
| AR-009 | Extend the existing outbox/inbox and retry dispatcher. Delivery failure shall not reverse a committed action; retry shall not duplicate delivery. Old notification links shall recheck current resource authorization. No email, real-time channel or frontend is included. |
| AR-010 | Notifications shall contain no CV, contact data, internal rejection reason or internal explanation. |
| AR-011 | Recruiter listing shall accept all five statuses; an omitted filter returns all statuses. Preserve existing fields, name search, ordering and pagination. Candidate listing retains its existing shape, extended additively with current version and Candidate-safe workflow information as needed. |
| AR-012 | Terminal access shall follow the details specification: Company contact, cover-letter and CV access stops after rejection or withdrawal. No automatic deletion or reopening is introduced. |

## Persistence and compatibility

Approved additive migration scope: expand Application statuses, add append-only history with rejection details, and extend notification application context. Preserve snapshots and committed migration files. Backfill existing SUBMITTED applications with submission entries using original Candidate and submission time; do not invent recruiter actions. Existing schema also admits VIEWED and REJECTED: inspect actual retained data before finalizing migration, and surface any rows without sufficient historical evidence rather than inventing transitions or reasons.

The existing optimistic version supports mutation concurrency. History and notification changes shall follow existing PostgreSQL/Flyway/JPA patterns. Coordinate clients to handle new status values and required mutation versions. The existing listing contract's SUBMITTED-only validation is explicitly superseded by AR-011 for this release.

## Human-approved HTTP contract

- `PATCH /api/companies/me/jobs/{jobId}/applications/{applicationId}/status`: `{ status, version, rejectionReason?, internalExplanation?, candidateFeedback? }`.
- `POST /api/candidate/applications/{applicationId}/withdraw`: `{ version }`.
- History is returned by role-specific detail endpoints with appropriate redaction.
- Validation: trimmed explanation and feedback up to 1000 Unicode code points each; OTHER explanation must be nonblank. Reject rejection-only fields on non-rejection transitions. Require nonnegative version.
- Return existing JSON envelopes; invalid input or unsupported transition is 400, stale version is 409, inaccessible resources use non-enumerating 404. Existing authentication and scoped account errors remain unchanged.

## Verification

Use existing PostgreSQL HTTP integration infrastructure to prove the transition matrix, skip-forward behavior, terminal guards, Candidate ownership, role/assignment isolation, stale concurrency, no-op retries, atomic history/outbox writes, notification retries and recipient deduplication, Candidate redaction, terminal privacy, migration/backfill and updated list filters. Run documented backend checks and replace only coverage superseded by this approved workflow.
