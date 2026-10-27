# Employer onboarding and email eligibility

Implements [GW-SR-REC-03](../software-requirements/rec/company-onboarding-verification-enhancement-spec.md),
[ADR 0007](../architecture/decisions/0007-email-only-employer-onboarding.md) and
[ADR 0008](../architecture/decisions/0008-canonical-account-phone.md).
The user approved the remaining concrete schema/API and scoped authorization in
sections 3–5 of the [implementation plan](../software-requirements/rec/company-onboarding-email-only-implementation-plan.md).
Delivery is backend/API plus HTTP examples. No source frontend exists in this
checkout; generated assets and historical UI notes do not establish a usable UI.
External clients must implement the restricted workspace and confirmation screen.

## Journey and permissions

Employer registration immediately creates one ACTIVE/USER account, an applicant
profile, a canonical declared phone and a 24-hour account/email-bound challenge
in one transaction. Email starts unverified. The declared Company name creates
no Company, membership or role. Registration and confirmation issue no session
or access/refresh tokens; the client signs in explicitly.

An unverified enrolled account can sign in, refresh/log out, read its auth and
onboarding state, and resend/confirm email or use existing password recovery/change.
Other scoped requests return 403 before Company preparation, upload/download,
dossier/history, invitation, notification or Candidate/recruitment effects.
The MVC gate checks both method and path; legal mutations and new scoped
invitation activation also check actual proof inside their business transaction.

Actual email proof requires a matching normalized email snapshot plus timestamp.
`users.email_verified` is compatibility data and cannot unlock an enrolled User.
Proof for an old/different email, wrong account, expired/replaced/consumed secret
fails. Concurrent confirmation permits one success. A GET/email preview never
consumes proof. Correct confirmation does not create a session or change roles.

After proof, an applicant without membership has `COMPANY_ACTION_REQUIRED`:
prepare a dossier and submit it, or explicitly accept a valid matching-email
invitation. Submission atomically creates PENDING Company, ACTIVE OWNER and
retained attempt; invitation acceptance creates ACTIVE RECRUITER in the invited
eligible Company. PENDING Owner access is read-only; REJECTED Owner may upload,
correct and resubmit the same dossier. Recruitment needs VERIFIED/non-banned
Company, active User/membership and the existing operation-specific permission.
Company Admin and Recruiter keep their existing roles after email confirmation;
verification never grants Owner or Staff privileges.

New applicants, already-enrolled accounts and enhanced-membership operation gates
use this rollout. Unrelated legacy accounts are not newly enrolled. Opening
onboarding state retains intentional enrollment for eligible existing Company
members. Ordinary un-enrolled legacy invitation recipients retain compatibility.
Historical invitation receipts never re-admit departed/removed members. New
applicants retain their Candidate profile/CV/Application restrictions after proof.
An already-active Recruiter-to-new-Owner transition remains deferred; no Company
membership constraint, departure or transfer flow is changed.

## API contract and client upgrade

Preserve `{status, message, data}` and current public/Staff portal/token contracts.
Secrets are body values, never query parameters. Use the examples in
[`company-onboarding.http`](../../backend/http/company-onboarding.http).

| Method and path | Contract |
|---|---|
| POST `/api/employer-registrations` | Same eight inputs: `fullName`, Vietnamese `phone`, `companyName`, `gender`, `workAddress`, `email`, `password`, `passwordConfirmation`. HTTP/envelope 201; data `{userId, emailVerified:false, stage:"EMAIL_REQUIRED", deliveryAccepted}`. No continuation/proof/session tokens. |
| GET `/api/me/employer-onboarding` | Current personal/provisional/version/origin/Company role fields; canonical User `phone`, actual `emailVerified`, stage `EMAIL_REQUIRED`, `COMPANY_ACTION_REQUIRED`, or current Company `PENDING`/`REJECTED`/`VERIFIED`. No `phoneVerified`. Existing member enrollment remains scoped. |
| POST `/api/me/employer-onboarding/email/send` | Authenticated, no body. HTTP 200 with `data.deliveryAccepted`; 60-second cooldown. Already verified retains scoped 400 behavior. |
| POST `/api/me/employer-onboarding/email/verify` | Authenticated `{emailSecret}`. HTTP 200, null data after single-use confirmation. Invalid proof returns scoped 400. No continuation/password or session creation. |
| POST `/api/employer-registrations/{id}/email/resend` or `/email/verify` | Retired: generic 410 independent of identifier/body/record existence, with no lookup, delivery or promotion. Fresh registration is required for old pending attempts. |
| POST `/api/me/employer-onboarding/phone/send` or `/phone/verify` | Same generic 410 after normal authentication, without enrollment, proof processing, delivery or writes. |
| PUT `/api/me/employer-onboarding/company` | Email-verified applicant without active membership: `companyName`, `workAddress`, `expectedVersion`. |
| POST `/api/me/employer-onboarding/documents` | Multipart PDF/JPEG/PNG up to 10 MiB; email proof required; preparation or rejected-Owner correction. Returns document ID/hash. |
| GET `/api/company-verification-documents/{id}` | Authenticated private attachment; actual proof when scoped. Uploader before submission, current Owner or Platform Staff afterward; no-store/nosniff. |
| POST `/api/companies/verification-request` | Email-verified applicant: `name`, `taxCode`, complete `legal`. Creates Company/Owner/attempt atomically. |
| PUT `/api/companies/me/verification` or `/api/companies/{id}/resubmit` | Rejected Owner only: `name`, `expectedVersion`, complete `legal`; retains Company ID/MST and all earlier evidence. |
| GET `/api/companies/me/verification/history` | Current Owner history; actual proof where scoped. Staff uses existing Staff history endpoint. |

`legal` includes `workAddress`, `submitterPosition`, `representation`
(LEGAL_REPRESENTATIVE/AUTHORIZED_REPRESENTATIVE), `declarationAccepted:true`, and
owned uploaded `documentId`. A declaration is not independent representation
proof; document validation checks basic byte signatures, not malware/forensics.
Staff decisions retain pending-only/version/reason/actor rules, CHECKED mapping,
immutable evidence and after-commit decision email.

Duplicate normalized email/phone returns safe 409 without another account's
profile, proof or Company details. Database constraints decide races. Duplicate
registration never implicitly resends email. Provider rejection, timeout,
unconfigured delivery or delivery exception after commit returns successful
account creation with `deliveryAccepted:false`; identifiers/account remain and
restricted login/authenticated resend still work. `true` means provider acceptance,
not mailbox arrival. Existing cooldown, expiry and replacement rules remain.
The retired pending-attempt hourly query is no longer an active dependency;
immediate uniqueness prevents repeated successful registrations for one email.
Broader aggregate registration/email abuse limits remain a production follow-up.

## Email configuration and delivery

Use environment-backed `ONBOARDING_EMAIL_FROM`, `ONBOARDING_EMAIL_CREDENTIAL`,
`ONBOARDING_CONFIRMATION_URL` and `ONBOARDING_ALLOWED_ORIGINS`. The confirmation URL
must be HTTPS and match an allowed origin, with no existing query/fragment.
The client reads the `memberEmailSecret` fragment, signs in as the correct account
if needed, POSTs the secret and removes it from browser history. No secret-bearing
GET endpoint, new token storage, SMS provider or OTP secret is introduced.

Decision delivery is scoped to enhanced dossier attempts. A committed decision writes an intent; a worker attempts up to eight provider submissions with exponential backoff capped at one hour. A stable idempotency key identifies the decision. A provider-accepted email sets `delivered_at`; this is provider acceptance, not proof of mailbox arrival. Delivery states/attempts remain in `company_decision_deliveries` for operator inspection. Missing credentials exhaust retries without reversing the decision; operators must configure delivery before enabling it for actual users.

The worker emits fixed WARN events `company_decision_email/retry_scheduled` and `company_decision_email/retry_exhausted` without recipients, provider bodies or secret values. After fixing delivery configuration, an authorized database operator can inspect exhausted records and explicitly requeue a selected decision (never rerun the business approval):

```sql
SELECT id, attempts, next_attempt_at FROM company_decision_deliveries
WHERE delivered_at IS NULL AND attempts >= 8;

-- Bind :decision_id to the reviewed UUID in the operator's database client.
UPDATE company_decision_deliveries
SET attempts = 0, next_attempt_at = CURRENT_TIMESTAMP, version = version + 1
WHERE id = :decision_id AND delivered_at IS NULL AND attempts >= 8;
```

Provider idempotency retention is finite; replay does not promise exactly-once mailbox
delivery. Real provider delivery and retention/disposal remain separate deployment checks.

## Migration and controlled cutover

`V2026.10.18.73` remains immutable history. Follow the existing
[canonical-phone cutover](canonical-account-phone.md#controlled-cutover) for V83.
For `V2026.10.27.84__bind_employer_email_proof.sql`:

1. Run the read-only [email preflight](../../backend/sql/employer-email-proof-preflight.sql)
   with an authorized operator account. Restrict report access; it contains personal
   email/proof timestamps and must not enter application logs or Git. Resolve all
   case/trim-normalized cross-account collisions through reviewed corrections.
   Review known historical email changes before binding old actual proof to the
   linked current account email; never guess proof ownership or merge Users.
2. Stop all old account/proof writers, including seeders, and recheck under cutover.
   Deploy backend/client changes together. Existing pending links now require fresh
   registration; existing Users, passwords, memberships, Company/evidence remain.
3. Flyway locks Users/profiles, blocks on unresolved email collisions, adds the
   unique `lower(btrim(users.email))` index and nullable `email_challenge_email` /
   `email_verified_email` profile columns. It binds only existing actual timestamps
   to the linked normalized email. It never rewrites stored User email or derives
   proof from the compatibility boolean. All steps are transactional.
4. Old pending records remain retained/inactive; no bulk promotion or transfer.
   Unbound old member challenge hashes remain stored but no longer verify: after
   the existing cooldown the signed-in member resends a bound challenge. New
   issuance snapshots email; confirmation records proof and clears active hash,
   snapshot and expiry. New local fixtures label their synthetic email proof and
   bind it to the fixture email; reruns preserve existing state.
5. Configure delivery and the external confirmation client, then verify registration,
   restricted login, correct-account proof, role/Company gates, expiry/resend,
   410 retirement and retained legal/review workflows before reopening writes.

Do not run old writers after cutover or roll back only the application binary:
the old journey can bypass bindings and promote retired data. Use a reviewed
forward fix or data-aware restore. No live database inventory/migration, real
email delivery or external UI/browser verification is claimed by automated tests.
Changing the account email, email recovery/support disputes, retention and
aggregate abuse policies remain separate reviewed work.

## Verification

Run from `backend/`: `docker compose -f docker-compose.test.yml up -d --wait`,
focused email/onboarding/migration tests, `./mvnw test`, and `./mvnw -DskipTests package`
after the full suite. Tests substitute delivery and cover binding, races,
transaction rollback, restricted access, scoped legacy behavior, role preservation,
retained evidence/moderation and Candidate contact compatibility. Provider acceptance
and browser/accessibility require an environment with real delivery/client setup.
