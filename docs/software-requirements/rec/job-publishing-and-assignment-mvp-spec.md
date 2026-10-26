# Job Publishing and Assignment MVP — Software Requirements Specification

| Field | Value |
|---|---|
| Specification ID | GW-SR-JOB-PUBLISHING-MVP |
| Project | GuardWork |
| Module | Job Publishing and Assignments |
| File | `job-publishing-and-assignment-mvp-spec.md` |
| Version | 0.1.0 |
| Date / Last Updated | 2026-10-26 |
| Owner | Thinh |
| Named human approver | Thinh (retained from baseline) |
| Status | Approved — explicit user approval recorded on 2026-10-20 |
| Ticket / branch | GW-78 / `GW-78-implement-job-publishing-and-assignment` |

## 1. Purpose and scope

Deliver backend Company job drafting, responsibility management, revision submission and review, approved public detail, pause/resume/close, deadline expiry and associated in-app notifications. Include Platform Staff queue, detail, revision history and approve/reject APIs. No frontend implementation is included.

Reopening, archiving, metrics, Platform Removal/appeals, search/discovery, Applications, interviews, offers and Company Admin capabilities are separate work. Existing Applications must eventually remain unaffected by closing/expiry; GW-78 introduces no Application storage, APIs, acceptance claims or synthetic metrics.

The broader [Job Publishing draft](../../feat/recruiters/job-publishing-spec.md) is background. This focused draft records the GW-78 interview's narrower scope and explicit changes: Owner responsibility may replace a Recruiter assignment; authorship grants no continuing access; Company Admin is excluded; and retained pending reviews may complete after closure/expiry without reopening. It does not silently amend the broader draft or other approved specifications.

## 2. Domain model and lifecycles

- A Job Posting retains its identity and Company across revisions.
- A Job Posting Revision contains candidate-facing content. Content becomes immutable at submission; moderation metadata may then complete once.
- The Published Revision is the approved revision shown publicly. Approval of a replacement preserves all former content and decisions.
- A Job Assignment grants a particular active Recruiter membership period current private job access. Historical authorship does not.
- Explicit Owner Responsibility is distinct from the Owner's authority over every Company job. It may coexist with any number of assigned Recruiters.
- At most one working revision, either DRAFT or PENDING_REVIEW, exists per Job Posting. A historical approved, rejected or withdrawn revision is never reused as a mutable draft.

```text
Job Posting: DRAFT --first approval--> PUBLISHED <--> PAUSED
                                      |              |
                                      +--> CLOSED <--+
                                      +--> EXPIRED <-+

Revision: DRAFT --> PENDING_REVIEW --> APPROVED | REJECTED | WITHDRAWN
```

Only PUBLISHED and PAUSED jobs undergo deadline expiry. CLOSED remains CLOSED after its deadline. CLOSED and EXPIRED cannot resume or reopen in GW-78. There is no hard-delete API.

## 3. Authorization and responsibility

| ID | Requirement |
|---|---|
| JP78-001 | Read current persisted User, Company, Company Membership and Job Assignment for every protected operation. Suspended Users, suspended Companies, inactive memberships and unrelated Companies cannot perform private job operations. Company Roles do not grant Platform Staff powers. |
| JP78-002 | Owner may access/manage every job in their Company. Recruiter may access a job only while assigned through their current active membership period. A Recruiter creator receives an assignment atomically with job creation. Owner-created jobs start with Owner Responsibility enabled. |
| JP78-003 | Only Owner may add/remove Recruiter assignments and enable/disable Owner Responsibility. Only active Recruiters of the same Company are assignable. Assignment takes effect immediately without acceptance; Recruiters cannot unassign themselves. |
| JP78-004 | Membership departure/removal ends all assignments of that membership atomically with revocation, preserving assignment history. Rejoining grants no old assignments, including where existing persistence reuses a membership identity. |
| JP78-005 | Having no responsible person is allowed. Derive `needsReassignment` when Owner Responsibility is false and no current active assigned Recruiter exists. This blocks submission/approval, flags the job and notifies Owner without unpublishing it. Restoration clears the flag. |
| JP78-006 | Company Admin is not enabled. Platform Staff can inspect/moderate any Company's submitted revisions, including their own Company. Platform authority alone grants no Company authoring/assignment power. |

## 4. Authoring, content and moderation

| ID | Requirement |
|---|---|
| JP78-010 | Authorized eligible members may create incomplete drafts before Company verification. Company suspension blocks private operations. Preserve existing personal email/phone verification and legacy-rollout policy; use the confirmed scoped route exceptions in the contract for pre-verification drafts, public detail and own-inbox access. |
| JP78-011 | Save incomplete drafts within type, enum, length and size limits. Submission requires complete content, a future exact deadline, verified non-suspended Company, eligible submitter and at least one responsible person. Return field-specific validation information through the existing scoped response convention. |
| JP78-012 | Required content comprises title, description, responsibilities, required/preferred qualifications, experience expectations, employment type, work arrangement, location, benefits, deadline, headcount, application instructions and a compensation range or explicit not-disclosed choice. Application instructions direct Candidates to apply through GuardWork. |
| JP78-013 | Narrative fields are plain text with line breaks, supporting Vietnamese Unicode. Screening questions are optional, capped at ten, ordered and typed SHORT_TEXT, SINGLE_CHOICE, YES_NO or NUMERIC, each with an explicit required flag. No automated ranking or rejection. Answer collection is out of scope. |
| JP78-014 | Preview returns the exact candidate-facing draft projection and validation issues. Submission requires explicit confirmation and the previewed current version; stale content cannot be submitted through an old confirmation. GW-78 delivers the backend contract; frontend preview display is later client work. |
| JP78-015 | Submission preserves exact content. Withdrawal before a decision marks the reviewed revision WITHDRAWN; create a separate derived draft to edit. Rejection retains its reason, reviewer and time; correction likewise derives a new draft. Every resubmission receives a distinct revision identity/review attempt. |
| JP78-016 | First approval publishes immediately. Later approval replaces Published Revision but preserves PUBLISHED, PAUSED, CLOSED or EXPIRED state after deadline reconciliation. Approval never resumes or reopens a job. A pending edit never changes public content. |
| JP78-017 | Approval requires future proposed deadline, current verified non-suspended Company and a responsible person. A deadline that passes in review causes approval failure; Company withdraws and submits corrected content. Administrator cannot rewrite content. Rejection requires a reason. |
| JP78-018 | Closing/expiry does not withdraw pending reviews. These reviews may still be approved or rejected under normal rules, but the Job Posting remains terminal. No new draft, edit or submission on CLOSED/EXPIRED is introduced by GW-78; existing drafts remain readable, and existing pending reviews may be withdrawn. |
| JP78-019 | Serialize withdrawal, approval and rejection so exactly one decision wins. Reject stale draft saves, conflicting working revision creation and stale moderation actions without overwriting history or duplicating notifications. |

## 5. Publication and public visibility

| ID | Requirement |
|---|---|
| JP78-020 | Owner and assigned Recruiters may pause PUBLISHED, resume PAUSED before its approved deadline while Company is verified, or close PUBLISHED/PAUSED. Cannot close an unpublished draft, resume a terminal job, delete history or publish without moderation. |
| JP78-021 | Use `now >= approvedDeadline` as expiry, including for paused jobs. Compare exact instants using a controllable Clock. Reconcile expiry before lifecycle decisions, replacement approval and public responses; a delayed worker never grants extra availability. A pending deadline extension has no effect until approved. |
| JP78-022 | Approval after the old approved deadline first recognizes expiry, then replaces approved content while preserving EXPIRED, even when the new deadline is future. CLOSED never becomes EXPIRED just because time passes. |
| JP78-023 | Public detail is anonymous and exposes only approved content, minimal public Company identity, operational state and availability. PAUSED/CLOSED/EXPIRED retain details with Applications unavailable. Jobs without an approved revision and jobs of suspended Companies return non-enumerating not found. |
| JP78-024 | Exclude draft content, private member identities/assignments, review feedback, tax identifiers and legal verification documents from public responses. No search/listing or Application endpoint is implied by public detail. |
| JP78-025 | Persist deadlines as exact instants. Require an explicit offset in API datetime values; clients display and default entry to Asia/Ho_Chi_Minh. A future Application feature must check current published deadline/state in its own transaction, never trust an earlier detail response. |

## 6. Notifications and audit

| ID | Requirement |
|---|---|
| JP78-030 | Notify affected Recruiter of assignment addition/removal, including autoassignment and membership-ended assignments; notify Owner and current assigned Recruiters of moderation approval/rejection; notify Owner when responsibility transitions from present to absent. Deduplicate recipients per event. |
| JP78-031 | Write business mutation, audit and notification intent atomically. Dispatch only after commit using existing inbox/retry recovery. Delivery failure cannot roll back committed job changes. No email integration is added. |
| JP78-032 | A recipient may read their own historical notification after losing access; following its job link rechecks current authorization. No moderation content is included in a removed assignee's minimal removal notification. |
| JP78-033 | Preserve actor, event type, Company/job/revision references, target membership where relevant and event time for creation, submissions, withdrawals, decisions, assignments, responsibility changes, lifecycle changes and expiry. Submitted content, review decisions and audit history are not deleted or overwritten. |

No new retention duration, indefinite legal retention policy or account-deletion mechanism is selected. Existing legally reviewed retention/deletion work remains separate; no Candidate CV or Application data is introduced here.

## 7. API, schema and verification

The [implementation contract](job-publishing-and-assignment-implementation-contract.md) contains endpoints, payloads, validation bounds, errors, migration structures, concurrency rules and test cases. User confirmation of that concrete contract and the explained concurrency outcomes is recorded on 2026-10-20, followed by explicit approval of the published issue on the same date.

Use existing feature-first controllers/services/Spring Data JPA, Flyway, PostgreSQL, authentication, envelopes, pagination, audit and notification patterns. No new dependency or platform-wide architecture is proposed. Existing API shapes remain compatible; notification context additions are nullable and additive.

## 8. Decision evidence and approval

Product decisions Q1–Q27 were confirmed in the GW-78 interview, including corrections at Q6, Q19 and Q21. Q25 permits terminal-state approval without reopening; Q26 confirms scoped onboarding compatibility changes. The user accepted the concrete Q27 contract, requested explanation of concurrency, and then agreed to the explained stale-save, competing-decision and access-revocation outcomes. See the workspace [decision log](../../../../../../.scratch/job-publishing-and-assignment/decisions.md) and root glossary/ADR.

On 2026-10-20, after publication of the consolidated tracker issue, the user explicitly instructed: "I approve the issue, write it down". Approved lifecycle status records that human decision; it is not an autonomous agent approval. The accompanying implementation contract is covered by the issue's approval. The user subsequently accepted the six-ticket breakdown and blocking edges on 2026-10-20, retaining the single-migration contract. On 2026-10-21 the user invoked implement-spec for the remaining backend work, treating ticket 01 as complete and naming the existing GW-78 worktree as the integration destination. No implementation, migration, commit or push has been performed as part of this interview.

## Implementation completion — 2026-10-21

The backend scope is implemented on `GW-78-implement-job-publishing-and-assignment`. Ticket 01 was already complete; tickets 02–06 and the final review fix are merged into that branch. The frontend and the committed single job migration were unchanged by the remaining-ticket implementation.

Verification passed: 250 backend tests, 46 final focused HTTP/security/PostgreSQL tests after the eligibility refactor, and Maven packaging. Specification review found no actionable gaps; standards review found no documented-rule violations, and its shared-eligibility duplication finding was addressed. See [backend workflow documentation](../../backend/job-drafts.md) and the backend README for routes and expiry configuration.

## Integration amendment — 2026-10-26

The user explicitly confirmed that Platform `STAFF` replaces the former Platform `ADMIN` terminology and authorized the migration renumbering described in [ADR 0007](../../architecture/decisions/0007-job-integration-migration-and-staff.md). Moderation reads current persisted `STAFF` authority; Company `ADMIN` remains excluded. Job moderation uses `/api/staff/...`, matching the baseline retirement of `/api/admin/**`; these job routes are new relative to `cc8943ab`. Compare integration behavior and migration ordering against `cc8943ab`.
