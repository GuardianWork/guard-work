# Shared registration and requested account-email proof

GW-SR-AUTH-03 replaces automatic email verification for newly registered Users.
`POST /api/auth/register` retains its five personal fields and 201 `UserDto`
response. It creates an ACTIVE USER with unverified email and sends no mail.
Sign in with `/api/auth/login`; Candidate and recruiting workspace navigation
remain in the existing public portal and grant no Company authority.

`GET /api/auth/me` exposes `emailVerified` and `recruitingEmailEligible`
(active and email-verified). These describe current persisted state, not cached
permissions. Active Users can use Candidate features before email verification.

Authenticated active public Users explicitly call
`POST /api/me/email-verification/request` without a destination address. Its
200 response reports `deliveryAccepted`, `alreadyVerified`, `nextRequestAt`
and `expiresAt`. Already-verified Users trigger no delivery. Provider acceptance
is not evidence of inbox delivery. Missing configuration and provider failures
report `deliveryAccepted:false` without rolling back the committed request.

The email contains a six-digit code. The signed-in client submits
`{ "emailSecret": "012345" }` to `/api/me/email-verification/confirm`.
Keep the code as a string to preserve leading zeroes. Never put it in URLs,
logs, analytics or stored navigation. Confirmation does not sign in or create
Company membership. Invalid, wrong-account, changed-email, expired, exhausted,
malformed and replayed proofs use the same 400 `40030 INVALID_EMAIL_PROOF`.

Codes expire at exactly 10 minutes. Five failed attempts invalidate the code;
the counter commits even when confirmation returns 400. Malformed code values
count, but unparseable JSON is rejected before the service. Resending before
60 seconds returns 429 `42930 EMAIL_REQUEST_COOLDOWN` and `Retry-After`;
at 60 seconds a replacement may be requested and resets the attempt count.
Each committed issuance invalidates the old proof even if delivery fails.
Issuance, failed guesses and confirmation serialize under the persisted User lock.
No plaintext code is stored and no automatic resend is added.

Account-code mail uses `ONBOARDING_EMAIL_FROM` and `ONBOARDING_EMAIL_CREDENTIAL`;
it does not require a frontend confirmation URL or origin allowlist.
Invitation links and their configuration remain unchanged.

Apply `V2026.10.30.85__account_email_verification_codes.sql` before this backend.
It adds `failed_attempts` and invalidates outstanding account verification links,
without changing verified accounts or invitations. Stop old verification writers
and deploy the separate frontend code-entry form with this backend. Users with
old account links must request a code; endpoint paths and `emailSecret` remain.
No frontend source exists in this checkout.

Migration `V2026.10.28.84__unified_registration_email_proof.sql` creates the
hashed account-email proof table without a cohort column. Baseline upgrades preserve
existing verification values and account/Company data. Verified Users stay eligible;
unverified Users must complete proof. Demo fixtures explicitly represent verified
accounts; their credentials remain unchanged.

Disposable databases with an earlier checksum for migration 84 or the removed migration
85 require rebuilding before startup. Flyway detects the changed checksum or missing
migration; do not mask divergent schema history with repair.

Stop old account/Company writers, apply migrations and deploy the replacement backend
and clients together. Binaries must be compatible with the schema; use forward repair if an older
binary requires a different schema. Old registration
writers automatically verify email and must not run in a mixed fleet. Do not reset
accounts or retained evidence. Account-email changes remain outside this feature.

Private Company job, membership and Owner invitation operations enforce the persisted
email gate for every User in their business transaction and at the scoped HTTP boundary.
The API reports 403 `40335 EMAIL_VERIFICATION_REQUIRED` when proof is missing. Clients
should show the email prerequisite, allow explicit request/confirmation, then retry the
intended operation using freshly read versions. Selecting Candidate or recruiting
workspaces never changes that authority. No legacy email exemption remains;
mandatory phone and old applicant restrictions do not control current
recruiting or Candidate access.

Active Users retain Candidate features, requested verification, own historical inbox
and current-User state independently of Company membership. Exact GET Company status,
Owner feedback/history and authorized evidence reads remain reachable under their
feature permissions. Corrections require email eligibility and REJECTED state. Suspended
Users receive 403 `40304 ACCOUNT_SUSPENDED` on protected operations, including account
state; anonymous recovery/public visibility retain their existing rules. Platform Staff
moderation remains governed by current Staff authority, independently of personal
recruiting proof. Approved public job content never changes solely because a member's
personal email eligibility changes.

Continue with [complete Company setup](company-setup.md) or [verified-email invitations](verified-email-invitations.md).
[Retired onboarding routes](employer-onboarding.md) return 410 without creating personal
profiles, provisional Companies or documents. See the [approved contract](../software-requirements/auth/unified-registration-implementation-contract.md)
for the complete operation matrix and coordinated client migration requirements.
