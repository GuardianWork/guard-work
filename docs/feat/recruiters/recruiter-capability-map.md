# Recruiter Capability Map

| Field | Value |
|---|---|
| Project | GuardWork |
| Owner | Thinh |
| Approver | Thinh |
| Status | Draft |
| Date | 2026-09-27 |
| Last Updated | 2026-09-27 |

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

| Reference use case | Destination | Disposition |
|---|---|---|
| UC-REC-01 Accept Company invitation | GW-SR-COMPANY-MEMBERSHIP | Baseline; uses Company Invitation and Company Membership terminology |
| UC-REC-02 Sign in and enter recruitment workspace | GW-SR-COMPANY-MEMBERSHIP plus platform authentication | Baseline; renamed Company Workspace; authentication mechanism deferred |
| UC-REC-03 View assigned jobs | GW-SR-JOB-PUBLISHING | Baseline; Owner/Admin have Company-wide access |
| UC-REC-04 Create and edit a job | GW-SR-JOB-PUBLISHING | Baseline; modeled through Job Posting Revisions |
| UC-REC-05 Submit a job for moderation | GW-SR-JOB-PUBLISHING | Baseline |
| UC-REC-06 Correct and resubmit rejected content | GW-SR-JOB-PUBLISHING | Baseline; creates a new review attempt |
| UC-REC-07 Manage active job state | GW-SR-JOB-PUBLISHING | Baseline |
| UC-REC-08 View Applications | GW-SR-APPLICATION-PIPELINE | Baseline |
| UC-REC-09 Update recruitment stage | GW-SR-APPLICATION-PIPELINE | Baseline; uses one Application Status plus append-only history |
| UC-REC-10 Add internal notes | GW-SR-APPLICATION-PIPELINE | Baseline |
| UC-REC-11 Reject an Application | GW-SR-APPLICATION-PIPELINE | Baseline |
| UC-REC-12 Reopen a terminal Application | GW-SR-APPLICATION-PIPELINE | Modified: rejected Applications can be reopened conditionally; withdrawn Applications cannot |
| UC-REC-13 Allow reapplication | GW-SR-APPLICATION-PIPELINE | Baseline; creates a new linked Application |
| UC-REC-14 Invite Applicant to Interview | GW-SR-INTERVIEW-OFFER | Baseline |
| UC-REC-15 Send and track Offer | GW-SR-INTERVIEW-OFFER | Baseline; Offer is not an employment contract |
| UC-REC-16 Record final hiring result | GW-SR-INTERVIEW-OFFER | Baseline; requires accepted Offer and Hiring Confirmation |
| UC-REC-17 Close a Job Posting with active Applications | GW-SR-JOB-PUBLISHING and GW-SR-APPLICATION-PIPELINE | Baseline; closure does not change Applications |
| UC-REC-18 Discover Candidates | Future Candidate discovery specification | Deferred |
| UC-REC-19 Send application invitation | Future Candidate discovery specification | Deferred |
| UC-REC-20 View or download a submitted CV | GW-SR-APPLICATION-PIPELINE | Baseline; individual access only |
| UC-REC-21 Report abuse | Shared abuse-report specification | Shared capability; not redefined here |
| UC-REC-22 Manage recruitment notifications | Shared notification specification | Shared capability; mandatory events identified by baseline specs |
| UC-REC-23 View activity and recruitment metrics | GW-SR-JOB-PUBLISHING and shared audit specification | Basic job-scoped counts only; advanced analytics deferred |

## Recommended delivery order

1. Approve Company Membership and the shared authorization vocabulary.
2. Approve Job Publishing and the Company verification/moderation dependencies.
3. Approve the consent-and-retention policy.
4. Approve the Application Pipeline.
5. Approve Interview and Offer behavior.
6. Approve shared API, authentication, notification, enforcement, and audit contracts before implementation depends on them.
