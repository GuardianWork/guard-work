# Recruiter Capability Map

| Field | Value |
|---|---|
| Project | GuardWork |
| Owner | Thinh |
| Approver | Thinh |
| Status | Draft |
| Date | 2026-09-27 |
| Last Updated | 2026-09-30 |

## Purpose

This non-normative map defines where Company recruitment capabilities are specified. It uses `recruiter use cases.pdf` as reference input, not as an approved source of truth.

A Recruiter is not a global User type. A User acts for one Company through an active Company Membership with the Recruiter Company Role. Owner and Admin are other Company Roles that can perform broader Company operations.

## Baseline specifications

| Specification | Responsibility |
|---|---|
| [GW-SR-COMPANY-MEMBERSHIP](company-membership-spec.md) | Company Invitations, Company Memberships, fixed Company Roles, ownership, access revocation, and Company Workspace access |
| [GW-SR-JOB-PUBLISHING](job-publishing-spec.md) | Job Assignments, Job Posting authoring, moderated revisions, publication lifecycle, and basic job reporting |
| [GW-SR-APPLICATION-PIPELINE](application-pipeline-spec.md) | Application submission, snapshots, review statuses, rejection, withdrawal, reapplication, notes, and CV access |
| [GW-SR-INTERVIEW-OFFER](interview-offer-spec.md) | Interview scheduling, Offer lifecycle, Candidate responses, and Hiring Confirmation |

## Delivery priorities

Priority controls delivery order, not requirement strength. When a capability is delivered, its authorization, privacy, validation, audit, and failure requirements remain mandatory.

| Priority | Meaning |
|---|---|
| **P0 — Core** | Required for the first usable Job Posting and Application-review release |
| **P1 — Important** | Required for the complete hiring lifecycle and thesis scope, but the P0 workflow can operate without it temporarily |
| **P2 — Enhancement** | Useful workflow improvement after P0 and P1 are stable |
| **Deferred** | Explicitly outside the current baseline |

| Capability | Priority | Rationale |
|---|---|---|
| Company Workspace, Company Membership, and RBAC enforcement | P0 | Establishes the security boundary for every Company operation |
| Company Invitations and Recruiter administration | P0 | Enables a Company to form its recruitment team |
| Ownership transfer | P1 | Necessary for continuity, but not part of daily recruitment |
| Job Assignments and assigned-job access | P0 | Prevents Recruiters from accessing unrelated Company Recruitment Data |
| Job Posting drafting and moderated publication | P0 | Required before Candidates can discover and apply to a Job Posting |
| Job Posting pause, close, and automatic expiry | P0 | Required to stop new Applications safely |
| Job Posting archive | P1 | Improves long-term record organization after recruitment ends |
| Application submission and immutable Application Snapshot | P0 | Starts the recruitment workflow and preserves submitted evidence |
| Application review, status changes, rejection, and withdrawal | P0 | Provides the minimum operational hiring pipeline |
| Individual CV view | P0 | Required to evaluate an Application |
| Individual CV download | P1 | Convenient but evaluation can use controlled in-platform viewing first |
| Internal Notes | P1 | Supports collaboration but is not required for a single Recruiter workflow |
| Reopen rejected Applications and grant Reapplication Permission | P1 | Handles exceptional correction and reconsideration paths |
| Bulk rejection | P2 | Improves efficiency but increases operational risk and is not required initially |
| Interview scheduling and Candidate responses | P1 | Interviews can initially be arranged outside GuardWork; in-platform tracking remains part of the thesis scope |
| Offer lifecycle and Hiring Confirmation | P1 | Completes the successful hiring path, but the first release can stop at Application review and status management |
| Basic job-scoped recruitment metrics | P2 | Useful for oversight but not required to conduct recruitment |
| Mandatory in-app business notifications | P0 | Communicates consequential workflow and access changes |
| Optional email copies and time-based reminders | P2 | Convenience layer; business actions remain available in GuardWork |
| Candidate discovery, proactive invitations, and talent pools | Deferred | Requires separate privacy, consent, and discovery specifications |
| AI ranking or automated hiring decisions | Deferred | Excluded from the controlled baseline |

The P0 release does not provide a complete in-platform successful-hire path. That path becomes complete in P1 when Offer lifecycle and Hiring Confirmation are delivered.

## Shared specifications required later

The baseline specifications depend on platform-wide contracts that are not selected here:

- authentication and session management;
- API conventions, pagination, and error envelopes;
- notification delivery and preferences;
- Company verification and platform enforcement;
- abuse reports and appeals;
- consent, personal-data retention, deletion, and legal review; and
- audit storage, pseudonymization, and access controls.

Detailed retention timing is an approval blocker. Application reopening, personal-data deletion timing, and post-process access cannot be approved until a separate consent-and-retention specification receives legal and human review.

## Baseline capability boundaries

The baseline includes Company Workspace access, assigned-job management, moderated Job Posting publication, Application review, Interviews, Offers, Hiring Confirmation, individual CV access, essential notifications, and basic job-scoped workflow reporting.

The baseline excludes:

- Candidate discovery, proactive application invitations, and talent pools;
- configurable interview scorecards;
- calendar and video-meeting integrations;
- electronic employment contracts and signatures;
- custom Company Roles and permissions;
- bulk CV export;
- AI ranking or automated hiring decisions;
- advanced or cross-Company analytics; and
- a concrete REST, authentication, persistence, or notification-delivery architecture.

## Cross-cutting experience requirements

- User-facing content uses Vietnamese, Unicode names and addresses, `vi-VN` formatting, and `Asia/Ho_Chi_Minh` as the default display zone.
- Technical documentation and internal status identifiers use English.
- Private-resource failures do not reveal whether another Company's resource or a privacy-hidden Candidate exists.
- User-facing errors are actionable without exposing internal moderation rules, stack traces, or storage identifiers.
- Core Company operations work by keyboard, do not communicate status by color alone, associate form fields with labels and errors, manage dialog focus, and identify affected records before confirmation.
- Opening an old notification always rechecks current authorization.

## Material differences from the reference PDF

- Recruiter is a Company Role, not a global User type.
- A User may hold at most one active Company Membership.
- Owner and Admin are also Company Roles.
- Users enter a Company Workspace rather than a Recruiter workspace.
- Recruiters access Job Postings they created or were assigned; Owner and Admin can access all Company Job Postings.
- A withdrawn Application cannot be reopened by a Company Member.
- Job Posting publication state and Job Posting Revision moderation state are separate.
- Candidate discovery and proactive invitations are deferred.
- Retention timing is unresolved and belongs to a separate approved specification.

## Reference use-case traceability

| Reference use case | Priority | Destination | Disposition |
|---|---|---|---|
| UC-REC-01 Accept Company invitation | P0 | GW-SR-COMPANY-MEMBERSHIP | Uses Company Invitation and Company Membership terminology |
| UC-REC-02 Sign in and enter recruitment workspace | P0 | GW-SR-COMPANY-MEMBERSHIP plus platform authentication | Renamed Company Workspace; authentication mechanism deferred |
| UC-REC-03 View assigned jobs | P0 | GW-SR-JOB-PUBLISHING | Owner/Admin have Company-wide access |
| UC-REC-04 Create and edit a job | P0 | GW-SR-JOB-PUBLISHING | Modeled through Job Posting Revisions |
| UC-REC-05 Submit a job for moderation | P0 | GW-SR-JOB-PUBLISHING | Core publication path |
| UC-REC-06 Correct and resubmit rejected content | P0 | GW-SR-JOB-PUBLISHING | Creates a new review attempt |
| UC-REC-07 Manage active job state | P0 | GW-SR-JOB-PUBLISHING | Pause, close, and expiry are core; archive is P1 |
| UC-REC-08 View Applications | P0 | GW-SR-APPLICATION-PIPELINE | Assigned-job access applies |
| UC-REC-09 Update recruitment stage | P0 | GW-SR-APPLICATION-PIPELINE | Uses one Application Status plus append-only history |
| UC-REC-10 Add internal notes | P1 | GW-SR-APPLICATION-PIPELINE | Collaboration enhancement |
| UC-REC-11 Reject an Application | P0 | GW-SR-APPLICATION-PIPELINE | Core terminal path |
| UC-REC-12 Reopen a terminal Application | P1 | GW-SR-APPLICATION-PIPELINE | Rejected Applications only and subject to retained data; withdrawn Applications cannot reopen |
| UC-REC-13 Allow reapplication | P1 | GW-SR-APPLICATION-PIPELINE | Creates a new linked Application |
| UC-REC-14 Invite Applicant to Interview | P1 | GW-SR-INTERVIEW-OFFER | Can be arranged outside GuardWork during P0 |
| UC-REC-15 Send and track Offer | P1 | GW-SR-INTERVIEW-OFFER | Offer is not an employment contract |
| UC-REC-16 Record final hiring result | P1 | GW-SR-INTERVIEW-OFFER | Requires accepted Offer and Hiring Confirmation |
| UC-REC-17 Close a Job Posting with active Applications | P0 | GW-SR-JOB-PUBLISHING and GW-SR-APPLICATION-PIPELINE | Closure does not change Applications |
| UC-REC-18 Discover Candidates | Deferred | Future Candidate discovery specification | Outside the baseline |
| UC-REC-19 Send application invitation | Deferred | Future Candidate discovery specification | Outside the baseline |
| UC-REC-20 View or download a submitted CV | P0 view / P1 download | GW-SR-APPLICATION-PIPELINE | Individual access only |
| UC-REC-21 Report abuse | Shared | Shared abuse-report specification | Priority belongs to the shared platform plan |
| UC-REC-22 Manage recruitment notifications | P0 essential / P2 preferences | Shared notification specification | Mandatory events are identified by baseline specs |
| UC-REC-23 View activity and recruitment metrics | P2 | GW-SR-JOB-PUBLISHING and shared audit specification | Basic job-scoped counts only; advanced analytics deferred |

## Recommended delivery order

1. Approve Company Membership, shared authorization vocabulary, and required platform security contracts.
2. Approve Job Publishing and the Company verification/moderation dependencies.
3. Approve the consent-and-retention policy.
4. Approve and deliver the P0 Application Pipeline.
5. Deliver P1 capabilities, including Interview scheduling, Offer lifecycle, and Hiring Confirmation.
6. Deliver P2 enhancements after P0 and P1 are stable.
