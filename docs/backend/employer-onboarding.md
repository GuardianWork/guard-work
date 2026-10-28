# Retired employer onboarding and retained decision delivery

The [approved unified registration contract](../software-requirements/auth/unified-registration-implementation-contract.md)
and [ADR 0008](../architecture/decisions/0008-unified-registration-and-company-setup.md)
replace separate employer signup, mandatory phone proof and provisional Company
setup. Historical approval evidence remains in [ADR 0005](../architecture/decisions/0005-staged-employer-onboarding.md)
and its linked requirements; it records the earlier contract.

Use [shared registration and requested account-email proof](unified-registration.md),
[complete Company submission/correction](company-setup.md), and
[verified-email invitations](verified-email-invitations.md). Candidate and recruiting
workspaces use one User and the public authentication context; workspace selection
grants no Company or Staff authority. No frontend source is present in this checkout.

## Retirement contract

The routes below return HTTP 410 with
`{status:410,message:"41030 ONBOARDING_RETIRED: Use shared registration and Company setup",data:null}`.
They do not bind old credentials, promote registrations, create profiles/Companies/documents
or send mail. Public registration routes remain anonymous; protected routes require
authentication and an active User. Malformed old request bodies still return 410.

| Method and path | Replacement |
|---|---|
| POST `/api/employer-registrations` | Shared `/api/auth/register`, then sign-in |
| POST `/api/employer-registrations/{id}/email/resend` and `/email/verify` | Authenticated requested account-email proof |
| GET `/api/me/employer-onboarding` | `/api/auth/me` and current Company status |
| POST `/api/me/employer-onboarding/email/send` and `/email/verify` | `/api/me/email-verification/request` and `/confirm` |
| POST `/api/me/employer-onboarding/phone/send` and `/phone/verify` | Removed prerequisite; no OTP replacement |
| PUT `/api/me/employer-onboarding/company` | No provisional-save replacement |
| POST `/api/me/employer-onboarding/documents` | File part of complete Company submission/correction |
| JSON-only Company submission/correction | Complete multipart dossier on the same routes |

Exact retired JSON handlers return 410 before email proof, while active multipart
handlers retain the email prerequisite and current service authorization. Existing
Users, Companies, membership periods, jobs, private documents, declarations and review
decisions remain intact. Incomplete employer registrations are never automatically
promoted; their old links are inert. The person may explicitly use shared registration
subject to official User uniqueness. Historical URL-only evidence is not fabricated
as retained bytes. No data reset or new retention/deletion policy is introduced.

## Mail configuration and recovery

The existing Resend adapter serves requested account-email proof and Company decisions.
Configure environment-backed `ONBOARDING_EMAIL_FROM`, `ONBOARDING_EMAIL_CREDENTIAL`,
`ONBOARDING_CONFIRMATION_URL` and `ONBOARDING_ALLOWED_ORIGINS`. Never commit credentials.
The confirmation URL points to the client handling `#emailSecret=...`, which requires
sign-in and explicit POST confirmation. Use HTTPS and an exact allowed origin. The
existing local exception permits HTTP only on literal `localhost`, for example:

```dotenv
ONBOARDING_CONFIRMATION_URL=http://localhost:5137/verify
ONBOARDING_ALLOWED_ORIGINS=http://localhost:5137
```

IP addresses, localhost subdomains/trailing-dot names, unexpected origins and URLs
with user information, query strings or fragments are rejected. A changed scheme or
effective port requires a matching allowlist entry. SMS/phone OTP runtime and
`ONBOARDING_OTP_SECRET` have been removed; this journey needs no SMS vendor.

Staff decisions atomically retain the Company mutation, immutable decision, audit
and delivery intent. Mail targets the submitting User's current account email.
The separate worker uses `employer.onboarding.delivery-worker-enabled` (default true)
and `employer.onboarding.delivery-poll-ms` (default 30000). It makes up to eight
provider attempts with exponential backoff capped at one hour and a stable decision
idempotency key. Provider acceptance does not prove inbox delivery. Delivery failure
never reverses the committed decision; warning categories omit recipients and secrets.

After repairing configuration, an authorized database operator can inspect exhausted
rows and explicitly requeue the reviewed decision without rerunning approval:

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

## Verification

Use the isolated PostgreSQL setup in [testing.md](testing.md). `RetiredOnboardingHttpTest`
checks every tombstone, no mutation/delivery, retained pending registrations and active
multipart gates. `EmployerOnboardingHttpTest` now exercises shared signup, Candidate
access before proof, complete submission, Staff review/correction, immutable private
evidence and failed/retried decision mail. Automatic decision scheduling is disabled
in tests; the worker is driven explicitly. Dedicated account-email, Company, invitation
and recruiting-access suites cover their own boundaries. Run `./mvnw test` and
`./mvnw package` from `backend/`; browser checks require the separate client source.
