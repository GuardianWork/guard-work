# Own Account Control — Use Cases

| Field | Value |
|---|---|
| Document ID | GW-AUTH-AC-UC |
| Work item | GW-95 |
| Status | Draft — interview scope confirmed; implementation scope approved in session on 2026-10-31; named approver not supplied |
| Owner | GuardWork thesis team |
| Named human approver | Not recorded; human designation and formal approval pending |
| Last updated | 2026-10-31 |
| Primary actor | Platform User (`USER`), including Candidates and Company members |
| Specification | [GW-SR-AUTH-04](../../software-requirements/auth/account-control-spec.md) |

## 1. Scope and common conditions

Manage Own Account concerns the caller's personal identity and access, including Users with Company `OWNER`, `ADMIN`, and `RECRUITER` roles. Platform `STAFF` is excluded from self-service. Company information, Candidate-specific profile content and CV management are separate workflows. Company membership never grants control of another User's personal account.

Normal operations require an authenticated active User and current server-side eligibility checks. Reactivation and pending-deletion cancellation instead use a restricted recovery journey that verifies credentials and an action-bound registered-email code before restoring access. A ban cannot be lifted through either journey. The linked implementation contract records the recovery mechanism approved in session.

Account and Candidate profile share first name, last name and the optional canonical phone. Account names take precedence over conflicting legacy Candidate names. Username and email cannot change. Unverified Users can view/edit personal details and verify the registered email; changing password or closing the account requires verified email.

Invalid input, stale state, incorrect credentials/proofs, authorization failures and unmet conditions leave business data unchanged. Proof issuance and failed-attempt counters may change. Recheck eligibility at commit; concurrent changes cannot bypass restrictions. Never expose passwords, hashes, tokens or verification codes in account views or audit details. Successful data-changing actions are audited using the existing mechanism. User-facing instructions are Vietnamese; technical documentation is English.

## 2. Inventory

| Stable ID | Use case | Requirements |
|---|---|---|
| UC-AUTH-AC-00 | Manage Own Account (overview) | REQ-AUTH-AC-01–13 |
| UC-AUTH-AC-01 | View Own Account | REQ-AUTH-AC-01, 03, 12 |
| UC-AUTH-AC-02 | Edit Shared Personal Details | REQ-AUTH-AC-02, 03, 12, 13 |
| UC-AUTH-AC-03 | Verify Registered Email | REQ-AUTH-AC-03, 12, 13; existing account-email contract |
| UC-AUTH-AC-04 | Change Password | REQ-AUTH-AC-04, 12, 13 |
| UC-AUTH-AC-05 | Deactivate Own Account | REQ-AUTH-AC-05–07, 11–13 |
| UC-AUTH-AC-06 | Reactivate Own Account | REQ-AUTH-AC-08, 11–13 |
| UC-AUTH-AC-07 | Request Account Deletion | REQ-AUTH-AC-05, 06, 09, 11–13 |
| UC-AUTH-AC-08 | Cancel Pending Deletion | REQ-AUTH-AC-10–13 |

## 3. Use-case descriptions

### UC-AUTH-AC-00 — Manage Own Account

- **Actor:** Non-Staff User.
- **Description:** Provide an entry point to personal account details, verification, credentials and voluntary account closure.
- **Pre-condition:** Active authenticated account; common conditions hold.
- **Post-condition:** The selected detailed use case completes, or the account remains unchanged.
- **Main flow:**
  1. The User opens account settings.
  2. The system displays the User's details and current verification state through UC-AUTH-AC-01.
  3. The User chooses Edit Personal Details, Verify Email, Change Password, Deactivate, or Request Deletion.
  4. The system explains prerequisites and starts the corresponding use case.
- **Alternatives:** An unverified User is guided to existing-email verification for restricted actions. An inactive User must use the applicable recovery journey rather than normal account settings. Staff and unauthorized callers are denied.
- **Constraints:** This overview adds no independent business transition or new login mechanism.

### UC-AUTH-AC-01 — View Own Account

- **Actor:** Active authenticated non-Staff User.
- **Description:** View personal identity, immutable identifiers, email-verification state and applicable account actions.
- **Pre-condition:** Common normal-operation conditions hold.
- **Post-condition:** Only the caller's permitted information is displayed; no data changes.
- **Main flow:**
  1. The User opens account settings.
  2. The system resolves the caller from trusted authentication context and verifies current eligibility.
  3. The system displays first name, last name, optional phone, username, email and email-verification state.
  4. The system marks username/email as fixed and explains applicable restrictions on available actions.
- **Alternatives:** Loading fails: show an error and allow retry. Missing/invalid authentication, inactive account, Staff role or another-account targeting: deny access without disclosing another account's information.
- **Constraints:** Read-only. Never display credential or proof material.

### UC-AUTH-AC-02 — Edit Shared Personal Details

- **Actor:** Active authenticated non-Staff User, including an email-unverified User.
- **Description:** Edit the personal identity shared by account settings and Candidate profile.
- **Pre-condition:** Common normal-operation conditions hold.
- **Post-condition:** Valid first/last names and optional phone are saved consistently for both surfaces; the change is audited.
- **Main flow:**
  1. The User opens personal-detail editing from account settings or Candidate profile.
  2. The system displays the canonical first name, last name and phone.
  3. The User changes names and/or changes or clears the phone, then saves.
  4. The system validates existing name rules and canonical Vietnamese-mobile normalization/global uniqueness.
  5. The system saves the shared identity consistently and records the successful change.
  6. Both surfaces show the same current identity on retrieval.
- **Alternatives:** Invalid/blank names or invalid phone: request corrected input. Duplicate phone: refuse that change. Username/email submitted for modification: reject. Concurrent updates or persistence failure: do not confirm a partial save.
- **Constraints:** No email verification or SMS proof is required. Existing account names resolve legacy name conflicts. Candidate-specific fields and submitted historical snapshots do not change. Structured-name API/migration details are selected by the linked implementation contract.

### UC-AUTH-AC-03 — Verify Registered Email

- **Actor:** Active authenticated non-Staff User.
- **Description:** Verify the existing registered email without changing its address.
- **Pre-condition:** Common normal-operation conditions hold.
- **Post-condition:** Successful proof marks the existing email verified; otherwise it remains unverified.
- **Main flow:**
  1. The User selects Verify Email.
  2. The system requests delivery of a verification code to the registered email through the existing account-email proof flow.
  3. The User enters the received code.
  4. The system validates the account/email-bound code under the existing expiry, attempt and resend rules.
  5. The system consumes the proof, marks email verified and records the successful change.
- **Alternatives:** Already verified: report that state without sending another code. Delivery failure: show the failure and allow a bounded retry; do not grant verification. Invalid, expired, exhausted or replayed code: refuse verification. Premature resend: explain the cooldown.
- **Constraints:** Preserve the [existing account-email contract](../../software-requirements/auth/unified-registration-implementation-contract.md#3-explicit-account-email-verification). Email-verification proof is not a closure/recovery authorization.

### UC-AUTH-AC-04 — Change Password

- **Actor:** Active authenticated non-Staff User with verified account email.
- **Description:** Replace the account password after current-password confirmation.
- **Pre-condition:** Common normal-operation conditions hold; account email is verified.
- **Post-condition:** New password is stored securely, all pre-existing sessions are invalidated, the change is audited and fresh sign-in is required.
- **Main flow:**
  1. The User selects Change Password.
  2. The User enters the current password and a new password.
  3. The system verifies current eligibility, the current password and existing new-password policy.
  4. The system changes the password securely, invalidates all existing access/refresh sessions and records success.
  5. The system directs the User to sign in with the new password.
- **Alternatives:** Unverified email: require UC-AUTH-AC-03 first. Incorrect password or weak new password: reject without a password change. A required update, invalidation or audit effect fails: do not confirm successful completion.
- **Constraints:** No new email code is required for this action. Password recovery remains a separate existing use case. The verified-email prerequisite and all-session invalidation are scoped amendments approved with the implementation contract.

### UC-AUTH-AC-05 — Deactivate Own Account

- **Actor:** Active authenticated non-Staff User with verified email.
- **Description:** Temporarily disable personal account access with a secure recovery path.
- **Pre-condition:** No Company ownership or active Company membership; common normal-operation conditions hold.
- **Post-condition:** Account is self-deactivated, all sessions are invalidated, Candidate discovery is hidden, new applications are blocked and prior visibility preference/history are preserved; action is audited.
- **Main flow:**
  1. The User selects Deactivate Account.
  2. The system checks closure eligibility and explains access, visibility, history and recovery consequences.
  3. The User supplies the current password; the system validates it and requests an action-bound code to the registered email.
  4. The User enters the code and explicitly confirms the consequences.
  5. The system validates the proof and rechecks current status, enforcement and membership/ownership.
  6. The system consumes the proof, applies the post-condition and reports completion.
- **Alternatives:** Owner: refuse closure; no ownership-transfer or Company-closure route is supplied by GW-95. Other active member: require the existing leave workflow first. Unverified email: require verification first. Incorrect credentials/proof, failed delivery, cancelled confirmation or changed eligibility: no deactivation. Incomplete mandatory effects: no successful completion.
- **Constraints:** Leaving a Company is separate and is not silently undone if deactivation later fails. No historical records or CV snapshots are erased. Deactivation cannot bypass or replace a Staff ban.

### UC-AUTH-AC-06 — Reactivate Own Account

- **Actor:** Non-Staff User whose account is self-deactivated.
- **Description:** Restore access after voluntary deactivation without restoring former sessions or memberships.
- **Pre-condition:** Account is self-deactivated; no current ban or independent restriction prevents recovery.
- **Post-condition:** Account access is restored subject to current permissions; prior Candidate visibility preference is restored subject to independent restrictions. Old sessions remain invalid; success is audited.
- **Main flow:**
  1. The User starts sign-in/recovery using existing account credentials.
  2. The system validates credentials and self-deactivated status without issuing a normal account session.
  3. The system requests delivery of a reactivation-bound code to the registered email.
  4. The User submits the code and confirms reactivation.
  5. The system validates/consumes the proof, rechecks current eligibility and restores the permitted account state and prior visibility preference.
  6. The system records success and directs the User to fresh sign-in.
- **Alternatives:** Active account: use normal sign-in. Deletion pending: use UC-AUTH-AC-08 instead. Banned account: deny reactivation. Invalid credentials/proof, delivery failure or changed state: do not restore access. Required state/visibility/audit effects fail: do not confirm success.
- **Constraints:** No previous Company membership, assignment, permission or session is revived. The linked implementation contract defines credential-based restricted recovery.

### UC-AUTH-AC-07 — Request Account Deletion

- **Actor:** Active authenticated non-Staff User with verified email.
- **Description:** Submit a pending permanent-closure request; permanent erasure is deferred.
- **Pre-condition:** No Company ownership or active Company membership; common normal-operation conditions hold.
- **Post-condition:** A pending deletion request is recorded; normal access is disabled, sessions invalidated, Candidate discovery hidden and new applications blocked. Prior visibility preference and historical records remain; action is audited.
- **Main flow:**
  1. The User selects Request Account Deletion.
  2. The system checks eligibility and explains that access will stop immediately, historical data remains, cancellation is possible before finalization and permanent processing is not delivered by GW-95.
  3. The User supplies the current password; the system validates it and requests a deletion-bound code to the registered email.
  4. The User enters the code and explicitly acknowledges the consequences.
  5. The system validates/consumes the proof, rechecks eligibility and records one pending request with all required access, visibility and audit effects.
  6. The system reports that deletion is pending, not completed erasure.
- **Alternatives:** Apply the Owner, membership, verification, proof, cancellation and failure alternatives of UC-AUTH-AC-05. A concurrent/already-pending request must not create a duplicate or overwrite its state. A self-deactivated User first completes UC-AUTH-AC-06.
- **Constraints:** No permanent deletion, automatic processing deadline or Staff review UI/API is included. Applications, submitted CV snapshots, Company/job records and audit evidence remain under existing access controls. Retention and erasure require separate approval.

### UC-AUTH-AC-08 — Cancel Pending Deletion

- **Actor:** Non-Staff User with a pending deletion request.
- **Description:** Withdraw the request before finalization and recover permitted account access.
- **Pre-condition:** The request remains pending; no ban or independent restriction prevents recovery.
- **Post-condition:** The request is cancelled, permitted account access and prior Candidate visibility preference are restored, old sessions remain invalid and success is audited. Departed Company memberships remain ended.
- **Main flow:**
  1. The User starts the pending-deletion recovery journey using existing account credentials.
  2. The system validates credentials and the pending request without issuing normal account access.
  3. The system requests a cancellation-bound code to the registered email and explains recovery consequences.
  4. The User supplies the code and confirms cancellation.
  5. The system validates/consumes the proof, rechecks pending status and enforcement, cancels the request and restores permitted access/visibility.
  6. The system records success and directs the User to fresh sign-in.
- **Alternatives:** No pending request, finalized request, ban or concurrent state change: refuse cancellation. Invalid credentials/proof or failed delivery: leave the request pending. Mandatory recovery effects fail: do not confirm successful cancellation.
- **Constraints:** GW-95 includes no finalization action, but cancellation must not imply that finalized erasure could later be reversed. No prior membership, assignment or session is restored.

## 4. Implementation and approval boundaries

The [supporting Draft specification](../../software-requirements/auth/account-control-spec.md#8-requirement-conflicts-gaps-and-approval-boundaries) records conflicts and deferred decisions. In particular:

- Existing Candidate full-name storage can diverge from account names. Shared structured-name editing and legacy reconciliation use the compatibility/migration design in the approved-in-session contract.
- Existing password-change behavior gains a verified-email restriction and stronger all-session invalidation.
- Owner transfer/Company closure is not currently available, so Owners remain blocked.
- Recovery for inactive accounts needs a narrowly scoped contract rather than relaxation of normal account authorization.
- Staff deletion processing, permanent erasure and retention schedules remain separate features. Pending requests cannot be described as completed deletion.
- Lifecycle-code bounds, API routes/errors, schema, state composition, concurrency and integration with Candidate discovery/applications are specified by the linked implementation contract. No infrastructure or dependency choice is made here.

The user subsequently approved implementation and the linked contract in session on 2026-10-31, including a local commit through the implement skill. Remote push is not authorized. Documents remain Draft because the human approver has not supplied a personal name; the session approval is recorded without inventing one.

### Current Company Admin closure limitation

The existing membership implementation permits voluntary leaving and Owner removal only for RECRUITER memberships. It does not permit either operation for Company ADMIN. An ADMIN can edit personal identity and change credentials but remains blocked from deactivation/deletion while that active membership exists. GW-95 preserves this constraint; an approved membership-policy change is needed to provide an exit path. Do not tell an ADMIN to use an unavailable leave/removal action.
