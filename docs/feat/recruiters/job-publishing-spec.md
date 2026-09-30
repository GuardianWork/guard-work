# Job Publishing — Software Requirements Specification

| Field | Value |
|---|---|
| **Specification ID** | GW-SR-JOB-PUBLISHING |
| **Project** | GuardWork |
| **Module** | Job Publishing |
| **File** | `job-publishing-spec.md` |
| **Version** | 0.1.0 |
| **Date** | 2026-09-27 |
| **Last Updated** | 2026-09-27 |
| **Owner** | Thinh |
| **Approver** | Thinh |
| **Status** | Draft |

## 1. Purpose and scope

This specification defines Company Job Assignments, Job Posting authoring, moderated candidate-facing revisions, publication state, closing behavior, and basic job-scoped reporting.

It does not define the Platform Administrator moderation queue, Company verification process, Application processing details, notification delivery, physical schema, API paths, or infrastructure.

## 2. Conceptual model

| Concept | Responsibility |
|---|---|
| Job Posting | The Company recruitment listing and its operational lifecycle |
| Job Posting Revision | One candidate-facing content version that becomes immutable when submitted for moderation |
| Published Revision | The approved revision currently presented to Candidates |
| Job Assignment | A Recruiter's authority and responsibility for one Job Posting |
| Platform Removal | Enforcement that makes a Job Posting unavailable without representing Company closure |

### 2.1 Job Posting lifecycle

```text
DRAFT -> PUBLISHED <-> PAUSED
              |          |
              +--> CLOSED
              +--> EXPIRED
CLOSED | EXPIRED -> ARCHIVED
```

Reopening `CLOSED` or `EXPIRED` requires a new approved revision. Platform Removal is an independent enforcement condition and is not `CLOSED`.

### 2.2 Job Posting Revision lifecycle

```text
DRAFT -> PENDING_REVIEW -> APPROVED
   ^             |
   |             +-> REJECTED
   +-------------+-> WITHDRAWN
```

An approved revision is immutable. Editing candidate-facing content creates another revision. A Published Revision remains public while another revision is drafted or reviewed.

## 3. Functional requirements

### 3.1 Assignment and access

| ID | Requirement |
|---|---|
| GW-JP-001 | A Recruiter who creates a Job Posting shall receive a Job Assignment to it automatically. |
| GW-JP-002 | Owner and Admin may access all Job Postings belonging to their Company. |
| GW-JP-003 | Owner and Admin may add or remove one or more Recruiters from a Job Posting. |
| GW-JP-004 | A Recruiter may access only Job Postings they created or currently hold a Job Assignment for. |
| GW-JP-005 | Removing the final Recruiter shall preserve the Job Posting and its Applications under Owner/Admin oversight and flag it for reassignment. |
| GW-JP-006 | Removing an assignment shall immediately remove the Recruiter's private Job Posting and Application access while preserving historical authorship. |

### 3.2 Drafting and submission

| ID | Requirement |
|---|---|
| GW-JP-010 | An authorized Company Member may create and save an incomplete revision as a draft. |
| GW-JP-011 | Submission shall require complete candidate-facing content, a future deadline, permitted screening questions, a verified non-suspended Company, and at least one assigned Recruiter. |
| GW-JP-012 | Required candidate-facing content shall include title, description, responsibilities, required and preferred qualifications, experience expectations, employment type, work arrangement, location, benefits, deadline, hiring headcount, and application instructions. |
| GW-JP-013 | A revision shall explicitly provide a valid compensation range with currency and pay period or select Compensation not disclosed. |
| GW-JP-014 | A valid compensation range shall have a minimum less than or equal to its maximum and shall not use misleading placeholders. |
| GW-JP-015 | Screening questions may be short-text, single-choice, yes/no, or numeric and may be required. |
| GW-JP-016 | Screening answers shall not automatically reject, rank, select, or hire Candidates in the baseline. |
| GW-JP-016A | A Job Posting Revision shall contain no more than ten screening questions. |
| GW-JP-017 | Submission shall show a candidate-facing preview and require confirmation. |
| GW-JP-018 | Submitting a revision shall make that exact revision immutable and place it in `PENDING_REVIEW`. |
| GW-JP-019 | An authorized member may withdraw a pending revision before a moderation decision, then edit a draft derived from it. |

### 3.3 Moderation and material edits

| ID | Requirement |
|---|---|
| GW-JP-020 | Every new Job Posting shall require Platform Administrator approval before publication. |
| GW-JP-021 | Approval of the first revision shall publish the Job Posting immediately. |
| GW-JP-022 | A moderation rejection shall preserve the reviewed revision, decision, reason, reviewer, and time. |
| GW-JP-023 | Resubmission after rejection shall create a new review attempt without overwriting prior history. |
| GW-JP-024 | A published Job Posting shall continue showing its Published Revision while a material edit is pending review. |
| GW-JP-025 | Material edits shall include title, description, responsibilities, qualifications, compensation, benefits, employment type, work arrangement, location, deadline, headcount, screening questions, and candidate-facing application instructions. |
| GW-JP-026 | Job Assignments, internal notes, and internal labels shall not change the Published Revision or require content moderation. |
| GW-JP-027 | Platform Removal shall make the Job Posting unavailable to Candidates, stop new Applications, preserve evidence, notify authorized Company Members, and expose an appeal route when the enforcement policy permits. |

### 3.4 Publication lifecycle

| ID | Requirement |
|---|---|
| GW-JP-030 | An assigned Recruiter or Owner/Admin may pause, resume, or close an eligible Job Posting. |
| GW-JP-031 | Pausing shall stop new Applications and remove the Job Posting from ordinary discovery while its detail URL states that Applications are temporarily unavailable. |
| GW-JP-032 | A paused Job Posting may resume without moderation only when candidate-facing content is unchanged and its deadline remains valid. |
| GW-JP-033 | Closing shall stop new Applications without changing existing Applications. |
| GW-JP-034 | GuardWork shall mark a Job Posting `EXPIRED` when its deadline passes and shall atomically reject later submission attempts. |
| GW-JP-035 | Deadline entry shall use an explicit local date and time, defaulting display to `Asia/Ho_Chi_Minh`, and persist an exact instant. |
| GW-JP-036 | Reopening a closed or expired Job Posting shall require a future deadline and a newly approved revision. |
| GW-JP-037 | Owner/Admin may archive a closed or expired Job Posting only when no Application has an active recruitment process. |
| GW-JP-038 | An archived Job Posting shall be read-only and absent from normal work queues. |
| GW-JP-039 | Closing, expiration, archive, or Platform Removal shall not erase Job Posting, revision, assignment, moderation, or Application history. |

### 3.5 Concurrency and reporting

| ID | Requirement |
|---|---|
| GW-JP-040 | GuardWork shall reject a stale draft save rather than silently overwrite another member's changes. |
| GW-JP-041 | Authorization and lifecycle eligibility shall be checked when an operation commits. |
| GW-JP-042 | Assigned Recruiters may view basic metrics only for assigned Job Postings; Owner/Admin may view them for all Company Job Postings. |
| GW-JP-043 | Baseline metrics may include views, Applications received, current counts by Application Status, hires, and median transition time. |
| GW-JP-044 | Baseline reporting shall not include protected-characteristic breakdowns, Candidate scoring, predictive analytics, or cross-Company benchmarks. |
| GW-JP-045 | GuardWork shall create mandatory in-app notifications for Job Assignment changes, moderation decisions, Platform Removal, and applicable security or enforcement events. |
| GW-JP-046 | Notification delivery failure shall not reverse an otherwise committed assignment, moderation, or lifecycle action. |

## 4. Role capability summary

| Capability | Assigned Recruiter | Owner/Admin | Platform Administrator |
|---|---:|---:|---:|
| Draft and edit revision | Yes | Yes | No |
| Submit or withdraw review request | Yes | Yes | No |
| Pause, resume, or close | Yes | Yes | No |
| Archive | No | Yes | No |
| Manage Job Assignments | No | Yes | No |
| Approve or reject revision | No | No | Yes |
| Apply Platform Removal | No | No | Yes |

## 5. Business rules

| ID | Rule |
|---|---|
| GW-JP-BR-01 | Public content always comes from one approved Published Revision. |
| GW-JP-BR-02 | A pending edit cannot alter public content before approval. |
| GW-JP-BR-03 | Only a verified, non-suspended Company can submit a revision. |
| GW-JP-BR-04 | Publication, pause, closure, expiration, archive, and Platform Removal have distinct meanings. |
| GW-JP-BR-05 | Closing or expiring a Job Posting does not end active Applications. |
| GW-JP-BR-06 | Scheduled publication is outside the baseline; approval publishes immediately. |

## 6. Failure catalogue

| Condition | Semantic result |
|---|---|
| Incomplete or invalid revision | Remains draft; invalid fields identified |
| Company unverified or suspended | Submission denied |
| Duplicate submission of pending revision | No second review request created |
| Stale edit | Save rejected; current revision must be reloaded |
| Lost assignment or membership | Read or write denied without partial change |
| Invalid lifecycle transition | Job Posting remains unchanged |
| Deadline reached during Application submission | No Application created |
| Unauthorized private identifier | Existence is not disclosed |

## 7. Verification scenarios

1. A Recruiter creates an incomplete draft and becomes assigned automatically.
2. A verified Company submits a complete revision and receives a moderation decision.
3. A rejected revision is corrected through a new review attempt with prior history intact.
4. A material edit waits for approval while the former Published Revision remains public.
5. Two members edit one draft; the stale save cannot overwrite the newer version.
6. A paused posting stops Applications and resumes without review only when eligible.
7. Closure with active Applications changes no Application Status.
8. An exact deadline race creates either one valid Application or none.
9. Platform Removal remains distinguishable from Company closure.
10. The final Recruiter is unassigned without deleting the Job Posting or Applications.
11. Notification delivery failure does not roll back a committed Job Posting action.
12. A revision with more than ten screening questions cannot be submitted for review.

## 8. Dependencies and unresolved contracts

- GW-SR-COMPANY-MEMBERSHIP
- Company verification and Platform Administrator moderation specifications
- GW-SR-APPLICATION-PIPELINE
- Shared API, authentication, notification, enforcement, appeal, and audit specifications
- Physical schema, indexes, and revision-storage design
