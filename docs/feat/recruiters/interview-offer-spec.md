# Interview and Offer — Software Requirements Specification

| Field | Value |
|---|---|
| **Specification ID** | GW-SR-INTERVIEW-OFFER |
| **Project** | GuardWork |
| **Module** | Interview and Offer Management |
| **File** | `interview-offer-spec.md` |
| **Version** | 0.1.0 |
| **Date** | 2026-09-27 |
| **Last Updated** | 2026-09-27 |
| **Owner** | Thinh |
| **Approver** | Thinh |
| **Status** | Draft |

## 1. Purpose and scope

This specification defines Interview proposals and responses, Offer creation and responses, and final Hiring Confirmation for an existing Application.

It does not define hosted video calls, external calendar synchronization, configurable scorecards, electronic employment contracts, digital signatures, payroll onboarding, physical storage, API paths, or delivery technology.

Interview, Offer, and attachment retention depends on the separate approved consent-and-retention specification.

## 2. Conceptual model

| Concept | Responsibility |
|---|---|
| Interview | One scheduled recruitment event and its response history for an Application |
| Offer | A tracked recruitment proposal that is not an employment contract |
| Hiring Confirmation | The authorized Company action that marks an Application `HIRED` after Offer acceptance |

### 2.1 Interview lifecycle

```text
PROPOSED -> CONFIRMED -> COMPLETED
    |           |
    +-----------+-> CANCELLED
    +-> DECLINED
```

A reschedule cancels the prior schedule and creates a linked replacement so history is preserved.

### 2.2 Offer lifecycle

```text
DRAFT -> SENT -> ACCEPTED
             |-> DECLINED
             |-> EXPIRED
             +-> WITHDRAWN
```

Only one Offer may be `SENT` for one Application at a time. Replacements preserve prior Offers.

## 3. Functional requirements

### 3.1 Interview proposal and response

| ID | Requirement |
|---|---|
| GW-IO-001 | An assigned Recruiter or Owner/Admin may propose one or more Interview time slots for an active authorized Application. |
| GW-IO-001A | An Interview proposal shall contain between one and five future, non-overlapping time slots. |
| GW-IO-002 | An Interview proposal shall include format, proposed times, expected duration, time zone, and Candidate-facing instructions. |
| GW-IO-003 | An in-person Interview shall include a location; a remote Interview shall include joining instructions and may include an external meeting link. |
| GW-IO-004 | GuardWork shall store exact instants and the scheduling time zone and shall default Vietnamese display to `Asia/Ho_Chi_Minh`. |
| GW-IO-005 | Sending the first Interview proposal shall move the Application to `INTERVIEW` when it is not already at that status or later. |
| GW-IO-006 | The Candidate may accept one proposed slot or decline the proposal. |
| GW-IO-007 | Candidate acceptance shall confirm the selected slot and make the remaining proposed slots unavailable. |
| GW-IO-008 | Candidate decline shall not automatically reject or withdraw the Application. |
| GW-IO-009 | Recruiter cancellation shall require a reason, notify the Candidate, and shall not automatically reject the Application. |
| GW-IO-010 | Rescheduling shall preserve the prior Interview and link a replacement proposal. |
| GW-IO-011 | Multiple Interviews may belong to one Application. |
| GW-IO-012 | Authorized members may add job-related Internal Notes after an Interview; configurable scorecards are outside the baseline. |

### 3.2 Offer creation and response

| ID | Requirement |
|---|---|
| GW-IO-020 | An assigned Recruiter or Owner/Admin may draft an Offer for an eligible active Application. |
| GW-IO-021 | An Offer draft shall not change Application Status. |
| GW-IO-022 | A sent Offer shall include position title, compensation and pay period, expected start date, response deadline, and Candidate-facing terms or instructions. |
| GW-IO-023 | A sent Offer may include benefits, workplace details, and a non-executable attachment. |
| GW-IO-024 | GuardWork shall clearly state that an Offer is not an electronic employment contract or digital signature. |
| GW-IO-025 | Sending shall require preview and confirmation, set the Offer to `SENT`, and move the Application to `OFFER`. |
| GW-IO-026 | Only one Offer may be `SENT` for an Application at a time. |
| GW-IO-027 | A Candidate may accept or decline a `SENT` Offer before its response deadline. |
| GW-IO-028 | An Offer response deadline shall be an exact instant between 24 hours and 30 calendar days after sending; GuardWork shall mark an unanswered Offer `EXPIRED` at that instant. |
| GW-IO-029 | Acceptance, decline, or expiry shall not automatically set Application Status to `HIRED`, `REJECTED`, or `WITHDRAWN`. |
| GW-IO-030 | After decline or expiry, an authorized member may send a replacement Offer, return the Application to a prior valid status with a reason, or reject it; the Candidate may withdraw. |

### 3.3 Offer withdrawal and correction

| ID | Requirement |
|---|---|
| GW-IO-040 | An assigned Recruiter or Owner/Admin may withdraw a `SENT` Offer before Candidate acceptance. |
| GW-IO-041 | Withdrawal shall require a reason, notify the Candidate, preserve the Offer, and restore the previous non-terminal Application Status. |
| GW-IO-042 | An accepted Offer shall not support ordinary edit or withdrawal. |
| GW-IO-043 | Correcting an accepted Offer shall require an exceptional Owner/Admin action, an audit reason, clear Candidate notification, and a new Offer requiring fresh acceptance. |
| GW-IO-044 | A replacement or correction shall not overwrite the prior Offer or its response. |

### 3.4 Hiring Confirmation

| ID | Requirement |
|---|---|
| GW-IO-050 | Candidate acceptance shall not automatically mark an Application `HIRED`. |
| GW-IO-051 | An assigned Recruiter or Owner/Admin may perform Hiring Confirmation only for an accepted current Offer. |
| GW-IO-052 | Hiring Confirmation shall require explicit confirmation, set Application Status to `HIRED`, append Application Status History, and notify the Candidate. |
| GW-IO-053 | A repeated Hiring Confirmation shall not create duplicate final results. |
| GW-IO-054 | Interviews and Offers for existing Applications may continue after the Job Posting closes or expires. |

### 3.5 Notifications and authorization

| ID | Requirement |
|---|---|
| GW-IO-060 | GuardWork shall create mandatory in-app notifications for Interview proposal, confirmation, rescheduling, cancellation, Offer sending, Offer withdrawal, Candidate Offer response, approaching expiry, and expiry. |
| GW-IO-060A | GuardWork shall create an in-app reminder 24 hours before a confirmed Interview when at least 24 hours remain after confirmation. |
| GW-IO-060B | GuardWork shall create an in-app reminder 24 hours before an unanswered Offer expires when its response window is at least 48 hours. |
| GW-IO-061 | Optional email copies shall contain minimal data and shall not attach CVs, detailed Offer terms, screening answers, or Internal Notes. |
| GW-IO-062 | Notification delivery failure shall not reverse an otherwise committed Interview, Offer, or Hiring Confirmation action. |
| GW-IO-063 | Opening an old notification shall require current authorization. |
| GW-IO-064 | A lost Company Membership or Job Assignment shall immediately prevent further private access or mutation. |

## 4. Business rules

| ID | Rule |
|---|---|
| GW-IO-BR-01 | An Interview response never makes an automated hiring decision. |
| GW-IO-BR-02 | Interview decline or cancellation does not automatically reject an Application. |
| GW-IO-BR-03 | An Offer is a recruitment proposal, not an employment contract. |
| GW-IO-BR-04 | Candidate Offer acceptance and Company Hiring Confirmation are distinct events. |
| GW-IO-BR-05 | Offer decline or expiry does not automatically end the Application. |
| GW-IO-BR-06 | Job Posting closure or expiry stops new Applications, not existing Interview or Offer workflows. |

## 5. Failure catalogue

| Condition | Semantic result |
|---|---|
| Invalid or past Interview time | Proposal not sent |
| Application is terminal or unauthorized | Interview or Offer operation denied |
| Candidate responds to obsolete schedule | Response denied; current proposal shown |
| Another Offer is already sent | Second Offer cannot be sent |
| Candidate responds after Offer deadline | Response denied; Offer is expired |
| Company withdraws accepted Offer through ordinary flow | Operation denied; exceptional correction required |
| Hiring Confirmation lacks accepted Offer | Application cannot become `HIRED` |
| Membership, assignment, or state changes before commit | Operation fails without partial change |

## 6. Verification scenarios

1. A Recruiter proposes several slots and the Candidate confirms one.
2. A declined Interview leaves the Application at `INTERVIEW`.
3. Rescheduling preserves the old schedule and Candidate response history.
4. Sending an Offer moves the Application to `OFFER`; saving a draft does not.
5. A second Offer cannot be sent while one is awaiting response.
6. Offer acceptance does not mark the Application `HIRED` until Hiring Confirmation.
7. Offer decline and expiry leave a deliberate next decision to the Company or Candidate.
8. Withdrawing a sent Offer restores the prior Application Status and notifies the Candidate.
9. Correcting an accepted Offer requires Owner/Admin and fresh Candidate acceptance.
10. A closed Job Posting still permits an existing Application's Interview and Offer workflow.
11. Lost assignment blocks an in-progress operation before commit.
12. Email-delivery failure does not roll back a committed business event.

## 7. Dependencies and unresolved contracts

- GW-SR-COMPANY-MEMBERSHIP
- GW-SR-JOB-PUBLISHING
- GW-SR-APPLICATION-PIPELINE
- Consent, retention, deletion, and legal-review specification — approval blocker
- Shared API, authentication, notification, audit, and enforcement specifications
- Physical schema, attachment storage, and external-link security architecture
