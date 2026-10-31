# Own account control (GW-95)

See the [frontend integration guide](account-control-frontend-integration.md) for screen flows, exact payloads, token clearing and error mapping.

The public portal uses personal account settings for all platform USER accounts, including Company members. STAFF is excluded. See the [requirements](../software-requirements/auth/account-control-spec.md), [implementation contract](../software-requirements/auth/account-control-implementation-contract.md) and [use cases](../feat/account/usecase.md).

## Identity and credentials

`GET /api/me/account` returns the caller's firstName, lastName, derived fullName, nullable phoneNumber, fixed username/email, emailVerified, selfServiceState, closureEligible and closureBlockers in the existing envelope. `PUT /api/me/account` accepts firstName, lastName and phoneNumber only. Names are trimmed and required, maximum 100 characters each. Phone accepts the existing Vietnamese mobile normalization and global uniqueness; omitted/null clears it, blank is invalid. Unverified active Users may view/edit these details.

Candidate `PUT /api/v1/candidates/profile` now requires firstName and lastName instead of writable fullName. Other fields keep their previous behavior. Legacy fullName writes are rejected with 400. Responses retain derived fullName and add firstName/lastName; fullName can reach 201 characters. Both surfaces use User identity, with no Candidate name copy. Account changes do not create Candidate profiles or rewrite historical documents.

Existing account-email verification proves only the fixed registered address. Existing password-change endpoint keeps its body, but platform USER must have verified email and provide the current password. Success invalidates all previous access/refresh sessions; sign in afresh. Staff password eligibility remains unchanged.

## Voluntary closure

`POST /api/me/account/deactivation/request-code` or `/deletion/request-code` accepts `{currentPassword}`. Confirmation returns `data.selfServiceState` (DEACTIVATED or DELETION_PENDING). The deletion success message explicitly says processing is pending. Confirmation on the corresponding `/confirm` accepts `{currentPassword,emailSecret,acknowledgeConsequences:true}`. All require an active non-Staff User, verified email, no Company ownership and no active membership. Owners remain blocked: no ownership transfer or Company closure is implemented here. Other members must complete a permitted existing leave flow; this feature does not expand leave permissions.

Request-code returns `{deliveryAccepted,nextRequestAt,expiresAt}`. Mail acceptance does not guarantee inbox delivery. Codes are six-digit strings, valid for 10 minutes, with five failed attempts and a 60-second resend cooldown per account/action. Resend replaces the previous proof even if delivery fails. Wrong-account/action, expired, consumed or exhausted codes cannot authorize a transition. Successful transitions invalidate every outstanding lifecycle proof for that User. Mail uses existing ONBOARDING_EMAIL_FROM and ONBOARDING_EMAIL_CREDENTIAL; no new configuration is needed.

Successful closure disables normal login/refresh/bearer access, invalidates existing sessions, hides an existing Candidate profile and saves its prior visibility preference. Deletion also records a pending request. Account, CV and Company/job/audit history is retained. GW-95 does not permanently erase data or provide Staff processing, and pending requests have no promised processing deadline.

## Restricted recovery

`POST /api/auth/account-recovery/request-code` accepts `{usernameOrEmail,password,action}` where action is REACTIVATE or CANCEL_DELETION. `/confirm` adds emailSecret and acknowledgeConsequences=true. Use the public portal header if supplied. Confirmation returns `data.selfServiceState: "ACTIVE"` without tokens. Recovery validates credentials and current persisted role, enforcement and voluntary state; no normal session is issued while inactive. A current ban or legacy inactive enforcement status blocks recovery.

Successful recovery restores normal-access eligibility and the prior Candidate visibility preference, cancels a deletion request where applicable and requires fresh sign-in. Old sessions and departed Company membership/assignments never return. Staff unban and ban expiry do not clear voluntary closure.

## Errors and auditing

Existing envelope/status conventions remain. New scoped messages are `40094 INVALID_ACCOUNT_ACTION_PROOF`, `40994 COMPANY_OWNERSHIP_EXISTS`, `40995 ACCOUNT_LIFECYCLE_CONFLICT` and `42994 ACCOUNT_ACTION_COOLDOWN` (Retry-After header). Existing email-verification, membership, credential and phone-conflict errors remain. Failed attempts do not change lifecycle or identity; security attempt counters do persist. Successful identity/password/lifecycle changes use existing audit records without credentials, proof values or personal snapshots.

## Deployment and limits

Migration V2026.10.31.94 adds independent self_service_state, saved visibility, action proofs and retained deletion requests; it drops duplicate Candidate full_name. Existing User names win legacy conflicts. Stop older Candidate writers and deploy the structured-name client with this backend. Do not roll back to an old writer against the new schema; a reverse migration requires review. Old Flyway migrations are unchanged.

This checkout has no frontend source, Candidate discovery endpoint or application-submission endpoint. Existing profile visibility and all normal account access are enforced; future discovery/applications must also check current lifecycle. Existing historical records and their current access controls remain. No new dependency or permanent-deletion worker is included.

### Current Company Admin closure limitation

The existing membership implementation permits voluntary leaving and Owner removal only for RECRUITER memberships. It does not permit either operation for Company ADMIN. An ADMIN can edit personal identity and change credentials but remains blocked from deactivation/deletion while that active membership exists. GW-95 preserves this constraint; an approved membership-policy change is needed to provide an exit path. Do not tell an ADMIN to use an unavailable leave/removal action.
