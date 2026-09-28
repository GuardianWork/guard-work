# Application Pipeline — Software Requirements Specification

| Field | Value |
|---|---|
| **Specification ID** | GW-SR-APPLICATION-PIPELINE |
| **Project** | GuardWork |
| **Module** | Application Pipeline |
| **File** | `application-pipeline-spec.md` |
| **Version** | 0.1.0 |
| **Date** | 2026-09-27 |
| **Last Updated** | 2026-09-27 |
| **Owner** | Thinh |
| **Approver** | Thinh |
| **Status** | Draft |

## 1. Purpose and scope

This specification defines Application submission, immutable Application Snapshots, review statuses, rejection, withdrawal, status correction, reapplication, internal notes, individual CV access, and closure interactions.

It does not define Candidate profile authoring, Candidate discovery, talent pools, AI ranking, bulk export, Interview and Offer records, physical storage, API paths, or detailed personal-data retention timing.

## 2. Approval blocker: consent and retention

Application reopening, personal-data deletion timing, and post-process access cannot be approved until a separate consent-and-retention specification receives legal and human review.

That specification must define the deletion trigger, any separately agreed correction or dispute period, withdrawal of agreement, legally required exceptions, anonymization, and how deletion applies to CVs, notes, Interviews, Offers, attachments, notifications, and audit data.

Requirements in this draft that depend on retained Recruitment Data are conditional on that policy.

Legal context for human review: Article 25 of Vietnam's Personal Data Protection Law No. 91/2025/QH15 addresses personal data in recruitment. See the [official law text](https://datafiles.chinhphu.vn/cpp/files/vbpq/2025/7/91qh.signed.pdf).

## 3. Conceptual model

| Concept | Responsibility |
|---|---|
| Application | One Candidate submission to one Job Posting and the recruitment process that follows |
| Applicant | The Candidate in the context of one Application |
| Application Snapshot | Immutable submitted data and the Published Revision answered by the Candidate |
| Application Status | The current recruitment step or terminal result |
| Application Status History | Append-only record of each transition and correction |
| Reapplication Permission | One-time authorization to create a new linked Application |
| Internal Note | Company-only job-related commentary with version history |

### 3.1 Application Status model

```text
APPLIED -> UNDER_REVIEW -> SHORTLISTED -> INTERVIEW -> OFFER -> HIRED
   |             |             |            |          |
   +-------------+-------------+------------+----------+-> REJECTED
   +-------------+-------------+------------+----------+-> WITHDRAWN
```

Authorized members may skip forward when appropriate. A status correction or reopening appends history; it never deletes a prior transition.

## 4. Functional requirements

### 4.1 Submission

| ID | Requirement |
|---|---|
| GW-AP-001 | A Candidate may submit only while the Job Posting accepts Applications. |
| GW-AP-002 | A Company Member shall not apply to a Job Posting belonging to their own Company. |
| GW-AP-003 | Submission shall require the specific agreement defined by the approved consent specification. |
| GW-AP-004 | Submission shall atomically capture the selected CV, cover letter if supplied, intentionally submitted profile fields, screening answers, and current Published Revision. |
| GW-AP-005 | Later Candidate profile, CV, or Job Posting changes shall not alter an Application Snapshot. |
| GW-AP-006 | A Candidate may have at most one active Application for one Job Posting. |
| GW-AP-007 | Submission shall be idempotent so a retry of the same attempt returns the created Application instead of creating a duplicate. |
| GW-AP-008 | Eligibility and deadline shall be checked at commit; failure shall create no partial Application. |
| GW-AP-009 | A successful submission shall set Application Status to `APPLIED`, append history, and notify the Candidate and authorized Company Members. |

### 4.2 Viewing and evaluating

| ID | Requirement |
|---|---|
| GW-AP-010 | Assigned Recruiters may view Applications only for assigned Job Postings; Owner/Admin may view all Applications belonging to their Company. |
| GW-AP-011 | An authorized view shall present the Application Snapshot, current Application Status, status history, and valid actions. |
| GW-AP-012 | Lists may be filtered only by job-relevant submitted information and workflow data. |
| GW-AP-013 | GuardWork shall prohibit filters or decision records based on sensitive or protected characteristics or clear proxies for them. |
| GW-AP-014 | Knowing an Application, Candidate, or CV identifier shall not grant access or disclose existence outside the authorized scope. |
| GW-AP-015 | Current authorization shall be rechecked when a protected read or write commits. |

### 4.3 Status transitions and corrections

| ID | Requirement |
|---|---|
| GW-AP-020 | An assigned Recruiter or Owner/Admin may move an active Application forward to an appropriate Application Status without visiting every intermediate status. |
| GW-AP-021 | Every transition shall append source, destination, actor, and time to Application Status History. |
| GW-AP-022 | A transition requiring an Interview, Offer, rejection reason, or Hiring Confirmation shall reject incomplete supporting data. |
| GW-AP-023 | Correcting an accidental non-terminal transition shall require a reason and append a correction event without deleting history. |
| GW-AP-024 | A stale concurrent status change shall fail and require refreshed state. |
| GW-AP-025 | An authorized member may reopen a rejected Application only while the Job Posting is not archived and the approved retention policy still makes its Recruitment Data available. |
| GW-AP-026 | Reopening shall require a reason, restore the last valid non-terminal status, preserve the rejection, and notify the Candidate. |

### 4.4 Rejection and withdrawal

| ID | Requirement |
|---|---|
| GW-AP-030 | Rejection shall require a job-related internal reason category. |
| GW-AP-031 | Rejection may include separate internal notes and optional constructive Candidate-facing feedback. |
| GW-AP-032 | Candidate-facing feedback shall never expose internal notes. |
| GW-AP-033 | Rejection shall set Application Status to `REJECTED`, append history, and send a neutral Candidate notification. |
| GW-AP-034 | A Candidate may withdraw from any non-terminal Application Status before `HIRED`. |
| GW-AP-035 | Withdrawal shall set Application Status to `WITHDRAWN`, append history, and stop further Company processing of that Application. |
| GW-AP-036 | A Company Member shall not reopen a withdrawn Application. |
| GW-AP-037 | Job Posting closure, expiration, archive, or Platform Removal shall not automatically reject or withdraw an existing Application. |
| GW-AP-038 | A Candidate personal-data deletion request during active recruitment shall initiate withdrawal, warn that recruitment cannot continue, and invoke the approved deletion process subject to its documented exceptions. |

### 4.5 Reapplication

| ID | Requirement |
|---|---|
| GW-AP-040 | An assigned Recruiter or Owner/Admin may grant one Reapplication Permission after a rejected or withdrawn Application. |
| GW-AP-041 | The grant shall require a reason, identify one Candidate and Job Posting, and be usable once. |
| GW-AP-042 | A Candidate may use it only while the Job Posting accepts Applications and no active Application exists. |
| GW-AP-043 | Reapplication shall create a new Application and Application Snapshot linked to, but not overwriting, the prior Application. |

### 4.6 Internal notes and CV access

| ID | Requirement |
|---|---|
| GW-AP-050 | Internal Notes shall be visible only to currently authorized Company Members for the Job Posting. |
| GW-AP-051 | Internal Notes shall concern job requirements and shall not contain sensitive, protected, retaliatory, or discriminatory commentary. |
| GW-AP-052 | An author may correct their note, but GuardWork shall preserve an auditable version history until the approved deletion policy applies. |
| GW-AP-053 | Candidates shall never see Internal Notes through ordinary Application workflows. |
| GW-AP-054 | An authorized Company Member may view or download one submitted CV at a time. |
| GW-AP-055 | CV download shall show a recruitment-purpose and retention-responsibility notice and record a sensitive-access audit event. |
| GW-AP-056 | GuardWork shall not provide bulk CV or Candidate-data export in the baseline. |
| GW-AP-057 | Sensitive-access audit shall cover CV views, CV downloads, and Offer attachment access; ordinary list rendering need not log one event per row. |

### 4.7 Closing and bulk actions

| ID | Requirement |
|---|---|
| GW-AP-060 | Closing a Job Posting shall not itself change any Application Status. |
| GW-AP-061 | Bulk rejection shall be a separate confirmed operation available to an assigned Recruiter for an assigned Job Posting and to Owner/Admin for any Company Job Posting; it shall preview each affected Application and Candidate notification. |
| GW-AP-062 | A bulk operation shall exclude Applications that changed after the preview and report them for individual review. |
| GW-AP-063 | GuardWork shall create mandatory in-app notifications for new Application, Candidate withdrawal, Candidate-visible status decision, and applicable security or enforcement events. |
| GW-AP-064 | Notification delivery failure shall not reverse an otherwise committed Application action. |

## 5. Business rules

| ID | Rule |
|---|---|
| GW-AP-BR-01 | Application Status is one value, not separate stage and outcome fields. |
| GW-AP-BR-02 | Application Status History is append-only. |
| GW-AP-BR-03 | An Application Snapshot never follows later Candidate profile, CV, or Job Posting edits. |
| GW-AP-BR-04 | Candidate withdrawal is terminal for that Application. |
| GW-AP-BR-05 | Reapplication creates a new Application. |
| GW-AP-BR-06 | No baseline feature automatically rejects, ranks, selects, or hires a Candidate. |
| GW-AP-BR-07 | Closing recruitment and deciding existing Applications are separate operations. |

## 6. Failure catalogue

| Condition | Semantic result |
|---|---|
| Job Posting is not accepting Applications | No Application created |
| Candidate belongs to the Company | Submission denied |
| Active or duplicate Application exists | Existing Application returned for same attempt, otherwise duplicate denied |
| Missing required answer or agreement | Submission denied with actionable fields |
| Lost membership or Job Assignment | Private access or mutation denied immediately |
| Stale Application state | Mutation denied; current state must be reloaded |
| Invalid transition | Application remains unchanged |
| Reopen attempted after data deletion | Reopen denied; new submission required if eligible |
| Bulk target changed after preview | Target excluded; remaining confirmed targets proceed atomically as defined by shared contract |

## 7. Verification scenarios

1. A Candidate submits against one Published Revision and later edits do not change the snapshot.
2. A deadline race creates either one valid Application or no Application.
3. Repeated submission with one idempotency identity cannot create duplicates.
4. A Recruiter skips from `APPLIED` to `INTERVIEW` and history records the transition.
5. A stale Recruiter update cannot overwrite a newer status.
6. A rejected Application is reopened with its rejection history intact while policy permits.
7. A withdrawn Application cannot be reopened by a Company Member.
8. Reapplication creates a new linked Application and consumes its permission once.
9. Removing a Job Assignment immediately blocks CV and Application access.
10. Closing a Job Posting leaves all existing Application Status values unchanged.
11. Sensitive Candidate attributes cannot be used as filters or recorded decision bases.
12. The approved retention process removes personal note text while preserving permitted pseudonymous audit facts.
13. A deletion request during active recruitment withdraws the Application and invokes the approved deletion process.

## 8. Dependencies and unresolved contracts

- GW-SR-COMPANY-MEMBERSHIP
- GW-SR-JOB-PUBLISHING
- GW-SR-INTERVIEW-OFFER
- Consent, retention, deletion, and legal-review specification — approval blocker
- Shared API, authentication, notification, audit, abuse-report, and enforcement specifications
- Physical schema, file storage, access-link, and deletion architecture
