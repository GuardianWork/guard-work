# GW-95 Own Account Control — Implementation Contract

| Field | Value |
|---|---|
| Contract ID | GW-SR-AUTH-04-CONTRACT |
| Parent | [GW-SR-AUTH-04](account-control-spec.md) |
| Status | Draft — human approved requirements, schema, Candidate API amendment and recovery design in session on 2026-10-31; named approver not supplied |
| Owner | GuardWork thesis team |
| Named human approver | Pending designation |
| Last updated | 2026-10-31 |

## 1. Delivery boundary

Backend-only implementation in this checkout; no frontend source is bundled. Implement account settings, shared identity, existing-email verification integration, password changes, voluntary lifecycle and restricted recovery. Do not add dependencies, Staff deletion processing, permanent erasure, ownership transfer, Company closure or application/discovery subsystems. Include frontend integration documentation and acceptance tests.

The Candidate discovery and application-submission endpoints do not currently exist in this backend. Closure will set existing Candidate profile visibility false and prevent normal account authentication/authorization. Future discovery/application implementations must enforce current User lifecycle and visibility; GW-95 cannot claim executable coverage of nonexistent journeys.

## 2. Schema

Use one new Flyway migration, numbered above all migrations present at implementation time. Existing migrations remain immutable.

- Add `users.self_service_state VARCHAR(30) NOT NULL DEFAULT 'ACTIVE'`, constrained to `ACTIVE`, `DEACTIVATED`, `DELETION_PENDING`. Leave current `users.status` and Staff ban history intact. Ordinary access requires BOTH status ACTIVE and self-service state ACTIVE. Existing rows retain their status and become self-service ACTIVE.
- Add nullable `users.prior_candidate_visibility BOOLEAN`. Capture it only on successful closure if a Candidate profile exists; restore and clear it on recovery. Do not create a Candidate profile for a non-Candidate account.
- Add append-preserved `account_deletion_requests`: generated BIGINT ID, User FK, requested timestamp, nullable cancelled timestamp. A partial unique index permits one uncancelled request per User. No finalized flag, erasure worker or Staff API is introduced. Successful cancellation closes the request rather than deleting history.
- Add `account_action_proofs`: User FK, purpose (`DEACTIVATE`, `DELETE`, `REACTIVATE`, `CANCEL_DELETION`), normalized-email snapshot, code hash, issued/expiry timestamps, failed-attempt counter; one current row per User/purpose. Consuming/exhausting clears the usable hash while preserving issuance time for cooldown enforcement.
- Reconcile Candidate profile identity using User first/last names. Remove duplicate `candidate_profiles.full_name`; return a derived `fullName` from User instead. Historical documents and submitted snapshots remain unchanged. Deploy with a coordinated Candidate client upgrade and stop older writers before migration. Rollback requires an explicit reverse migration to reconstruct names, not reusing an older application against the changed schema.

The requesting human approved this schema in session on 2026-10-31; migration V2026.10.31.94 implements it.

## 3. Identity and password API

Preserve existing envelopes and portal separation. Services use trusted caller identity, current persisted state and the established User/Company locking order. Only active platform USER can use new normal account endpoints.

| Endpoint | Request | Success |
|---|---|---|
| `GET /api/me/account` | None | Own firstName, lastName, derived fullName, phoneNumber, username, email, emailVerified, selfServiceState, closure eligibility/blockers |
| `PUT /api/me/account` | Required firstName/lastName; nullable phoneNumber | Updated own account projection |
| Existing email verification request/confirm | Unchanged | Existing behavior |
| Existing `PUT /api/auth/change-password` | Unchanged currentPassword/newPassword | Existing success envelope; all old sessions invalid |

New account-edit requests explicitly reject attempted username/email updates. Preserve required trimmed names of 1–100 characters each and shared optional phone normalization/uniqueness. Omitted/null phone follows the existing full-update clearing convention; blank is invalid.

Breaking Candidate request amendment: `PUT /api/v1/candidates/profile` replaces writable `fullName` with required `firstName` and `lastName`. Reject the legacy fullName input rather than silently ignoring it. Keep the remaining profile fields and existing phone convention. Response retains derived `fullName` and adds firstName/lastName. The derived string may reach 201 characters; clients must accommodate it. Profile updates and account settings both write User names/phone in the same transaction as their applicable existing profile changes. Staff cannot use the Candidate path to bypass the self-service exclusion.

For platform USER password changes, require verified email and current password under the User lock, update the password, invoke existing sessionVersion increment/all-refresh revocation and audit atomically. Preserve Staff password-change scope and prerequisites. Do not change forgot/reset-password requirements; disabled accounts must remain unable to obtain a normal session even after password reset.

## 4. Closure API

| Endpoint | Request | Success |
|---|---|---|
| `POST /api/me/account/deactivation/request-code` | currentPassword | Delivery acceptance, nextRequestAt, expiresAt |
| `POST /api/me/account/deactivation/confirm` | currentPassword, emailSecret, acknowledgeConsequences=true | Deactivated; no tokens |
| `POST /api/me/account/deletion/request-code` | currentPassword | Delivery acceptance, nextRequestAt, expiresAt |
| `POST /api/me/account/deletion/confirm` | currentPassword, emailSecret, acknowledgeConsequences=true | Deletion pending; no tokens |

Both issuance and confirmation require active non-Staff USER, verified email, no Company ownership and no active membership. Recheck every prerequisite at confirmation. Read ownership independently of active membership so inconsistent/legacy ownership cannot bypass the block. Existing leave workflow remains explicit and separate.

On success, consume proof; change self-service state; preserve prior profile visibility and hide an existing profile; invalidate sessions with existing enforcement service; append deletion request where applicable; write audit in the same transaction. No remote mail sending occurs inside this transition transaction. Repeated confirmations cannot create duplicate transitions/requests.

## 5. Restricted recovery API and proof security

Expose only these narrowly scoped credential-based routes under the existing auth namespace:

| Endpoint | Request | Success |
|---|---|---|
| `POST /api/auth/account-recovery/request-code` | usernameOrEmail, password, action (`REACTIVATE` or `CANCEL_DELETION`) | Generic delivery-acceptance projection; no access/refresh token |
| `POST /api/auth/account-recovery/confirm` | usernameOrEmail, password, action, emailSecret, acknowledgeConsequences=true | Recovery complete; no tokens; User must sign in afresh |

Do not reuse normal login sessions or allow inactive Users through normal bearer authentication. Verify password before sending any recovery mail. Resolve account, lock/recheck password and persisted role/status/self-service state, reject Staff/wrong portal, reject unresolved bans and ineligible lifecycle. Resolve expired bans through the existing enforcement mechanism; clearing a ban does not clear voluntary lifecycle. Existing portal context is preserved and public recovery cannot grant Staff access.

Recovery requires no Company membership restoration; Company restrictions remain intact. Reactivation requires DEACTIVATED; cancellation requires DELETION_PENDING plus a matching pending request. Atomically consume proof, cancel the request if applicable, set self-service ACTIVE, restore previous profile visibility only when independent restrictions allow it, clear saved visibility and record audit. Do not issue tokens or revive prior sessions.

Use the current email adapter, Clock, cryptographically random six-digit ASCII code and SHA-256 hashed storage, bound to User/email/purpose. Select existing email-proof bounds: 10-minute expiry, five failed code attempts, 60-second per-User/purpose resend cooldown. Resend supersedes older codes for that action even if later mail delivery fails; unsuccessful delivery does not change lifecycle. Keep hashes/codes/credentials out of responses, logs and audit. Lock issuance/attempt accounting; persist failed-code counters on rejection. Another action's code never authorizes the requested action. Invalid credentials produce a generic failure without revealing account state; lifecycle details are disclosed only after valid credentials. This scoped proposal adds no project-wide rate-limit infrastructure.

## 6. Enforcement, concurrency, audit and errors

Extend the existing persisted-state/sessionVersion checks for login, refresh and bearer access to require self-service ACTIVE. Preserve legacy SUSPENDED/DELETED and BANNED handling. Independent self-service state prevents Staff unban or automatic expiry from reviving a deactivated/deletion-pending account. Ban/recovery races recheck serialized current state.

Follow the established Company-before-User lock order where membership/enforcement requires it. Guard closure versus joining/creating Company membership on the same User boundary; inspect all participating writers and test races. Identity updates and proof/lifecycle operations serialize on that User. Profile visibility writes also honor lifecycle and cannot republish an inactive profile.

Use existing AuditService and audit_logs without renaming its legacy actor column. Record the acting User ID, action, target and minimal changed field names/lifecycle facts; do not introduce private credential/proof values or complete personal snapshots. Audit and mandatory state/session effects join the business transaction. Existing Staff personal-history filters remain unchanged.

Preserve existing error envelope and established errors when semantically applicable. Scoped proposed failures: 400 invalid input/password/proof/acknowledgement, 401 invalid credentials/session, 403 Staff/wrong portal/verification required/enforcement, 409 duplicate phone/ownership/membership/lifecycle conflict, 429 resend cooldown with Retry-After. New codes are 40094 INVALID_ACCOUNT_ACTION_PROOF, 40994 COMPANY_OWNERSHIP_EXISTS, 40995 ACCOUNT_LIFECYCLE_CONFLICT and 42994 ACCOUNT_ACTION_COOLDOWN; existing codes are unchanged. Database/enforcement failure fails closed and must not be reported as successful recovery.

## 7. Verification and documentation

Use the existing Maven/JUnit/MockMvc and isolated PostgreSQL test strategy; add no test framework. Start with failing tests at account service/controller, identity DTO/service and persisted lifecycle/enforcement seams. Run focused tests and compile regularly, then the full clean test suite once and package for migration/configuration changes. Include PostgreSQL migration/constraint and transaction tests, proof attempt/expiry/resend/replay tests, portal/Staff exclusion, all-session invalidation and ban/closure/recovery/membership race coverage. Remove only tests for explicitly superseded Candidate independent-name/password behavior and replace them with approved coverage.

Update backend README, account frontend integration guide and relevant auth/Candidate requirements with approved amendment references. Record the approved identity/lifecycle/recovery choice in an ADR. Permanent erasure and nonexistent application/discovery endpoints remain explicit delivery limitations.

## 8. Approval provenance

The requesting human approved GW-SR-AUTH-04 and its amendments, the schema above, the breaking Candidate name contract and coordinated rollout, and the scoped inactive-account recovery/proof authorization design in session on 2026-10-31. Record the human approver by name before marking any specification Approved. Existing user authorization covers implementation intent and the implement skill's eventual local commit; it does not authorize remote push or a PR.

### Current Company Admin closure limitation

The existing membership implementation permits voluntary leaving and Owner removal only for RECRUITER memberships. It does not permit either operation for Company ADMIN. An ADMIN can edit personal identity and change credentials but remains blocked from deactivation/deletion while that active membership exists. GW-95 preserves this constraint; an approved membership-policy change is needed to provide an exit path. Do not tell an ADMIN to use an unavailable leave/removal action.
