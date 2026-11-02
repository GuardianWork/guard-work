# [GW-SR-AUTH-04] Own Account Control — Software Requirements Specification

| Field | Value |
|---|---|
| Specification ID | GW-SR-AUTH-04 |
| Project | GuardWork |
| Module | Common Authentication & Identity |
| Work item | GW-95 |
| File | `account-control-spec.md` |
| Version | 0.1.0 |
| Date / Last updated | 2026-10-31 |
| Owner | GuardWork thesis team |
| Named human approver | Not recorded; human designation and formal approval pending |
| Status | Draft — product decisions confirmed in interview; implementation scope approved in session on 2026-10-31; named approver not supplied |

## 1. Introduction and scope

Define personal account control for platform `USER`, including Candidates and Users with Company `OWNER`, `ADMIN`, or `RECRUITER` membership. Platform `STAFF` is excluded from these self-service use cases. Company membership grants no access to another person's account.

The user confirmed the GW-95 design in Q1–Q28 and subsequently explicitly approved the implementation contract, including requirement amendments, schema, breaking Candidate API and recovery design on 2026-10-31. The scoped amendments and technical choices are authorized by that session approval; permanent deletion remains unauthorized and deferred. See [use cases](../../feat/account/usecase.md).

Included: view account; edit shared personal names/phone; verify the existing email; change password; deactivate/reactivate; submit/cancel a deletion request. Username and email are immutable through this feature. Candidate-specific skills, education, experience, CV management, Company information, ownership transfer, Company closure, Staff review of deletion requests, and permanent erasure are outside scope.

## 2. Overall description

### 2.1 Actors and access

Normal account viewing and editing require an authenticated, currently active non-Staff User. Email verification is not required for viewing or name/phone edits. Password changes and account closure require a verified registered email. All ownership and eligibility checks use trusted server context and current persisted state.

Inactive Users cannot use normal protected account operations. Reactivation and pending-deletion cancellation are narrowly scoped recovery journeys using credentials and an action-bound code sent to the immutable registered email; they must not grant normal sessions before successful recovery. The approved-in-session [implementation contract](account-control-implementation-contract.md) specifies the restricted recovery mechanism. Staff bans and other enforcement restrictions cannot be bypassed by recovery.

### 2.2 Conceptual lifecycle

The following are business conditions, not selected database enum values:

| From | Action | Result |
|---|---|---|
| Active | Deactivate with required confirmations | Self-deactivated |
| Self-deactivated | Password and email-code confirmation | Active, subject to independent restrictions |
| Active | Submit deletion request with required confirmations | Deletion pending; normal access disabled |
| Deletion pending | Cancel before finalization using password and email code | Active, subject to independent restrictions |
| Deletion pending | Staff finalization | Deferred; no finalization or erasure mechanism in GW-95 |

A Staff ban is independent from self-deactivation/deletion intent. Recovery cannot clear it. No transition between self-deactivated and deletion-pending conditions is defined here; the User first completes the applicable recovery flow. No automatic deletion, deadline, grace period, or automatic Staff processing is selected.

## 3. Logical data and identity ownership

The existing User is the personal identity for both account settings and Candidate profile. First name and last name are canonical; the Candidate name is derived from those fields using the existing first-name/last-name composition convention. Both editing surfaces use these structured fields rather than independently editing an unrelated full name. Existing conflicting profile names yield to the User's first/last name. Both surfaces expose the same optional canonical phone.

Candidate-specific profile content remains separate. Editing account identity must not create a Candidate profile for every Company member merely to store identity. Existing CV files, submitted CV snapshots, application history, Company records and audit evidence are not rewritten by a name edit or closure.

Lifecycle intent, pending request, prior profile visibility, action proof, and successful activity records must be represented sufficiently to enforce these rules. Physical storage and migration choices are recorded in the linked implementation contract; migration V2026.10.31.94 implements them.

## 4. Functional requirements

Requirement IDs use the scoped `AUTH-AC` family to avoid collisions with existing Authentication requirements.

| ID | Requirement | Use case |
|---|---|---|
| REQ-AUTH-AC-01 | The system shall let an active non-Staff User view their own account names, phone, immutable username/email, email-verification state and applicable lifecycle options without exposing secrets. | UC-AUTH-AC-01 |
| REQ-AUTH-AC-02 | The system shall let that User edit first name, last name and optional phone without requiring email verification. Account and Candidate surfaces shall read and edit the same identity; existing name conflicts shall use account names. | UC-AUTH-AC-02 |
| REQ-AUTH-AC-03 | The system shall reject changes to username/email through account-control operations and offer verification only for the registered email through the existing account-email proof journey. | UC-AUTH-AC-01, 02, 03 |
| REQ-AUTH-AC-04 | The system shall require verified account email and the correct current password to change password, validate the new password under the existing password policy, and invalidate all existing sessions on success. Subsequent access requires fresh sign-in. | UC-AUTH-AC-04 |
| REQ-AUTH-AC-05 | The system shall require verified email, current password, an action-bound email code and explicit acknowledgement of consequences before deactivation or deletion submission. | UC-AUTH-AC-05, 07 |
| REQ-AUTH-AC-06 | The system shall block closure while the User owns a Company or retains an active Company membership. Non-owners must complete the existing Company-leave workflow first. | UC-AUTH-AC-05, 07 |
| REQ-AUTH-AC-07 | Successful deactivation shall disable normal account access, invalidate all sessions, hide the Candidate profile from discovery and prevent new applications while preserving historical records and prior visibility preference. | UC-AUTH-AC-05 |
| REQ-AUTH-AC-08 | The system shall allow a self-deactivated User to reactivate using valid credentials and a registered-email code, without lifting bans or restoring departed/revoked Company membership. Profile visibility shall return to its prior preference subject to independent restrictions. | UC-AUTH-AC-06 |
| REQ-AUTH-AC-09 | A deletion submission shall record a pending request, disable normal account access, invalidate sessions and apply the same profile/application restrictions and preservation rules as deactivation. No permanent erasure shall occur through GW-95. | UC-AUTH-AC-07 |
| REQ-AUTH-AC-10 | The system shall let the User cancel their pending deletion before finalization using password and an action-bound email code, restoring access and prior profile visibility subject to independent restrictions. Old sessions and departed memberships shall not revive. | UC-AUTH-AC-08 |
| REQ-AUTH-AC-11 | Lifecycle proofs shall be single-use, expiring, bounded in attempts/resends and bound to User, registered email and action. A proof for one action shall not authorize another. Reuse the account-email proof conventions where applicable; lifecycle-code limits are selected in the implementation contract. | UC-AUTH-AC-05–08 |
| REQ-AUTH-AC-12 | Failed actions shall preserve account identity, lifecycle, membership and visibility state. Invalid proof attempts may update security counters. The system shall explain unmet conditions without exposing secrets or another account's data. | All |
| REQ-AUTH-AC-13 | Successful data-changing actions shall be auditable through the existing audit mechanism. Passwords, code values, hashes of credentials/proofs and tokens shall not be recorded in activity details. | UC-AUTH-AC-02–08 |

## 5. Business rules

| ID | Rule |
|---|---|
| BR-AUTH-AC-01 | Staff self-service account control is excluded; Company roles do not exclude otherwise eligible Users. No operation targets another User. |
| BR-AUTH-AC-02 | Preserve the existing shared-registration name rules (trimmed, required first/last names, each 1–100 characters), existing password policy and canonical optional-phone format/uniqueness. Phone may be cleared; no SMS verification is introduced. |
| BR-AUTH-AC-03 | Email verification proves the existing immutable email. It does not change that email or authorize a different lifecycle action. |
| BR-AUTH-AC-04 | Company ownership blocks closure. Existing membership rules provide no ownership-transfer or Company-closure flow; Owners remain blocked until a separately approved solution exists. Do not promise a usable transfer route. |
| BR-AUTH-AC-05 | Leaving a Company is a separate explicit operation, with its existing assignment/access effects. Account closure does not silently leave a Company or delete Company/job history. |
| BR-AUTH-AC-06 | Session invalidation covers all devices and pre-existing access/refresh sessions after password change, deactivation or deletion submission. Reactivation/cancellation does not revive old sessions. |
| BR-AUTH-AC-07 | Closed-account profiles are hidden; existing applications, submitted CV snapshots, Company/job records and audit evidence are preserved with existing access controls. No new retention duration or purge rule is established. |
| BR-AUTH-AC-08 | Preserve the previous Candidate visibility preference across deactivation/deletion pending. Recovery respects current bans, profile moderation, membership, Company status and verification rules. |
| BR-AUTH-AC-09 | A pending deletion is a request, not completed erasure. No Staff queue, decision action or promised completion time is delivered by this scope. Users must see that processing is pending. |

## 6. Failure and concurrency requirements

Reject missing/expired authentication for normal operations; reject Staff, another-account targets and current enforcement restrictions. Reject blank/invalid names, invalid/duplicate phones, weak passwords, incorrect current passwords, unverified-email restricted actions, invalid/expired/replayed/exhausted codes, premature resend and ineligible lifecycle states. Explain Company-ownership/membership blockers and the pending-deletion limitation.

Recheck eligibility and lifecycle state when committing an action, including concurrent Company membership, bans, cancellation and closure requests. Competing actions must not overwrite each other or produce duplicate active deletion requests. Identity changes across both surfaces must succeed consistently; required lifecycle, visibility, session and audit effects must not be reported as successful if incomplete. Email delivery failure grants no authority or lifecycle transition; users may retry under bounded resend rules. Issuance/attempt counters can change independently of the business action. The implementation contract selects transactional state/audit/session effects, persisted proof-attempt accounting and serialized current-state rechecks.

## 7. API, schema and client impacts

The linked implementation contract records the approved scoped endpoints, schema, recovery mechanism and breaking Candidate API amendment. Preserve unrelated existing contracts; no new dependency or infrastructure is selected.

Known client impact: Candidate profile currently accepts `fullName`; canonical structured-name editing may require coordinated changes. Account settings need immutable email/username presentation, email-verification guidance, lifecycle confirmation and restricted recovery screens. Normal inactive-account authorization must not be relaxed to make recovery work. User-facing instructions and email copy must be Vietnamese, with Unicode names and `vi-VN` / Vietnam-time presentation; code and technical documentation remain English.

## 8. Requirement conflicts, gaps and approval boundaries

| Source / current behavior | GW-95 proposal and required follow-up |
|---|---|
| [Approved Authentication](authentication-authorization-spec.md), REQ-AUTH-16 requires current password; its password-change description revokes refresh tokens | Add verified-email prerequisite and invalidate every existing session. The scoped amendment and session-enforcement choice are recorded in the linked contract. Staff password behavior is not redefined by this non-Staff scope. |
| [Candidate Draft](../../feat/candidates/spec.md), independent full name; current service copies account names initially then edits profile name independently | Use one account identity on both surfaces; reconcile existing conflicting names in favor of User names. The linked contract specifies the coordinated breaking Candidate request change, derived-name display, length bounds, migration and stopping old writers. |
| [Canonical phone ADR](../../architecture/decisions/0008-canonical-account-phone.md) | Preserve nullable globally unique normalized Vietnamese phone; account settings extend eligible writers without selecting a migration here. |
| Approved Authentication gestures at self-deletion but has no usable deletion contract; Candidate Draft describes future erasure | GW-95 only records/cancels pending requests and preserves data. Permanent erasure and retention need separate requirements approval. |
| [Company membership](../rec/company-membership-mvp-spec.md), [job publishing](../rec/job-publishing-and-assignment-mvp-spec.md) | Preserve membership/assignment/history rules. Owner closure remains unavailable; no new transfer or Company-closure workflow. |
| [Staff account control](../adm/account-management-spec.md), [enforcement ADR](../../architecture/decisions/0009-account-enforcement.md) | Self-service lifecycle must remain distinct from bans; recovery must enforce current bans and session validity. The linked contract selects independent state composition and serialized rechecks. |
| Existing account-email proof requires an active authenticated User | Reuse applicable proof security conventions, not that endpoint's active-account prerequisite for recovery. The linked contract specifies restricted recovery and lifecycle-code limits. |

The requesting human approved the requirements, amendments and implementation contract in session on 2026-10-31. The contract resolves lifecycle/proof bounds, recovery, schema and Candidate-client migration choices. A personal approver name has not been supplied, so formal lifecycle metadata remains Draft rather than inventing a name. Discovery/application subsystems and permanent erasure remain explicit integration/deferred boundaries.

## 9. Acceptance scenarios and verification plan

1. Candidate and each Company role can view/edit only their own account; Staff cannot use these self-service operations.
2. Unverified Users can edit valid names/phone and verify their existing email, but cannot change password or close the account.
3. Edits through either identity surface appear in both; conflicting legacy names resolve to account first/last names. Candidate-only content and historical snapshots remain unchanged.
4. Username/email edits, invalid names/phones, duplicate phones, incorrect passwords and weak passwords fail without business-state changes.
5. Verified password change invalidates all prior access/refresh sessions; new sign-in succeeds with the new password.
6. Owner closure is blocked. Other active Company members must leave first; leaving preserves history and ends assignments under the existing contract.
7. Closure succeeds only with all confirmations; profile discovery/new applications and old sessions are blocked, historical data remains, and an audit record exists.
8. Expired, replayed, wrong-account/action and exhausted codes fail; resend limits and delivery failure cannot authorize a transition.
9. Reactivation/cancellation requires password and email proof, restores prior visibility subject to restrictions, and restores neither old sessions nor Company membership.
10. Concurrent bans/closure/recovery/membership changes cannot bypass eligibility. Pending deletion produces no physical erasure or Staff finalization in GW-95.
11. Failure of a mandatory effect does not produce a reported successful partial action or expose secrets in logs/history.

Implementation verification uses the existing Maven/MockMvc/PostgreSQL strategy. Executable account-control HTTP and migration tests cover the applicable backend scenarios; the integration guide identifies absent frontend/discovery/application components and deferred erasure. Test execution results are reported in the task handoff rather than inferred from this plan.

### Current Company Admin closure limitation

The existing membership implementation permits voluntary leaving and Owner removal only for RECRUITER memberships. It does not permit either operation for Company ADMIN. An ADMIN can edit personal identity and change credentials but remains blocked from deactivation/deletion while that active membership exists. GW-95 preserves this constraint; an approved membership-policy change is needed to provide an exit path. Do not tell an ADMIN to use an unavailable leave/removal action.
