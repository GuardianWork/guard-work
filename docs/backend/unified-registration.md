# Shared registration and requested account-email proof

GW-SR-AUTH-03 replaces automatic email verification for newly registered Users.
`POST /api/auth/register` retains its five personal fields and 201 `UserDto`
response. It creates an ACTIVE USER with unverified email and sends no mail.
Sign in with `/api/auth/login`; Candidate and recruiting workspace navigation
remain in the existing public portal and grant no Company authority.

`GET /api/auth/me` adds `emailVerified`,
`recruitingEmailVerificationRequired`, `recruitingEmailEligible`, and
`candidateAccessAllowed`. These describe current persisted state, not cached
permissions. Existing enrolled applicants regain implemented Candidate access.

Authenticated active public Users explicitly call
`POST /api/me/email-verification/request` without a destination address. Its
200 response reports `deliveryAccepted`, `alreadyVerified`, `nextRequestAt`
and `expiresAt`. Already-verified Users trigger no delivery. Provider acceptance
is not evidence of inbox delivery. Missing configuration and provider failures
report `deliveryAccepted:false` without rolling back the committed request.

The link carries a private secret in the URL fragment. The receiving client must
sign in, clear the fragment, and explicitly POST `{ "emailSecret": "..." }` to
`/api/me/email-verification/confirm`. It must never put the secret in query
parameters, logs, analytics or stored navigation. Confirmation does not sign in
or create Company membership. Invalid, wrong-account, changed-email, expired,
malformed and replayed proofs use the same 400 `40030 INVALID_EMAIL_PROOF`.

Proofs expire at exactly 24 hours. Resending before 60 seconds returns 429
`42930 EMAIL_REQUEST_COOLDOWN` and `Retry-After`; at 60 seconds another request
is allowed. Each committed issuance invalidates the old proof, even if delivery
fails. Confirmation and issuance serialize under the persisted User lock; one
confirmation wins. No plaintext proof is stored and no automatic resend is added.

Existing `ONBOARDING_EMAIL_FROM`, `ONBOARDING_EMAIL_CREDENTIAL`,
`ONBOARDING_CONFIRMATION_URL`, and `ONBOARDING_ALLOWED_ORIGINS` configure the
existing adapter. The exact-origin HTTPS policy and allowlisted localhost HTTP
exception remain. Links now use `#emailSecret=...`; the retired employer/member
confirmation flow is not a caller of this endpoint. No frontend source exists in this checkout;
HTTP examples document the client transition, and browser work belongs to the
client repository.

Migration `V2026.10.28.84__unified_registration_email_proof.sql` preserves both
historical email flag values and marks existing Users exempt using the persisted
`recruiting_email_verification_required=false` cohort. Its database default marks
later insertions true. Shared registration explicitly sets the marker true and
email false. The private proof table stores only hash, bound account email,
issuance/expiry and consumption timestamps. Local demo fixtures explicitly declare
legacy exemption; their existing credentials are preserved.

Stop all old account/Company writers before applying this migration and deploy
the replacement backend and clients together. Old code writes automatically
verified flags; the cohort default alone cannot make a mixed fleet safe. Keep
additive data on rollback and disable registration, Company submission, invitation
acceptance and private recruiting until forward repair. Do not reset existing
accounts, Companies or retained evidence. Account-email changes are outside this
feature; no existing application writer changes that address.

Private Company job, membership and Owner invitation operations enforce the persisted
new-User email gate in their business transaction and at the scoped HTTP boundary.
The API reports 403 `40335 EMAIL_VERIFICATION_REQUIRED` when proof is missing. Clients
should show the email prerequisite, allow explicit request/confirmation, then retry the
intended operation using freshly read versions. Selecting Candidate or recruiting
workspaces never changes that authority. Legacy Users remain exempt from this new
personal gate; mandatory phone and old applicant restrictions do not control current
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
