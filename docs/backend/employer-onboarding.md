# Retired employer onboarding and retained decision delivery

The [approved unified registration contract](../software-requirements/auth/unified-registration-implementation-contract.md)
and [ADR 0008](../architecture/decisions/0008-unified-registration-and-company-setup.md)
replace separate employer signup, mandatory phone proof and provisional Company
setup. Historical approval evidence remains in [ADR 0005](../architecture/decisions/0005-staged-employer-onboarding.md)
and its linked requirements; it records the earlier contract.

## Target amendment — 2026-10-27 (partially implemented)

The user confirmed [GW-SR-REC-03 v0.2.1](../software-requirements/rec/company-onboarding-verification-enhancement-spec.md): create an official account immediately, permit restricted sign-in before email proof, enforce unique email/declared phone across existing accounts, and remove SMS gates. Email proof must precede Company preparation/submission and Recruiter invitation acceptance. Initial dossier submission still creates PENDING Company and ACTIVE OWNER atomically; invitation acceptance creates ACTIVE RECRUITER for the invited Company. Recruitment still needs an eligible VERIFIED Company and current authorization. Already-enrolled accounts must lose SMS prerequisites; unrelated legacy accounts are not newly enrolled. The already-ACTIVE Recruiter-to-new-Owner case is explicitly undecided.

Follow-up Q10–Q17 confirms backend-only delivery, one shared User phone, coordinated client changes, retained accounts on mail-submission failure, 410 retirement of old pending-proof/SMS routes, blocking data conversion on unresolved phone conflicts, Candidate edits against the shared source and authenticated cross-device email proof. See the [current gap and implementation plan](../software-requirements/rec/company-onboarding-email-only-implementation-plan.md) for source evidence, proposed schema/API and test/release sequence.

Source inventory correction: no source `frontend/` directory exists in this checkout. The Maven resource declaration and historical UI checkpoints below do not demonstrate a currently available UI. Generated `target/` assets are not an authorized source to edit or reconstruct. The next implementation delivers backend contracts/request examples; external UI remains a separate dependency.

The user approved and this release implements the [canonical account phone portion](canonical-account-phone.md): blocking migration/preflight, shared Candidate contact updates, coordinated employer writers/fixtures, 410 SMS retirement, and removal of SMS prerequisites. `phoneVerified` is omitted from current onboarding state. Historical phone/proof remains stored. The immediate-account registration replacement, pending-email route retirement and new email-proof binding are separate follow-up work.

Historical UI delivery notes below do not establish frontend source availability. No production SMS integration is required; old migrations and evidence remain intact.

## Journey and compatibility

Open `/` on the backend origin for the Vietnamese onboarding interface. Register personal details and provisional Company information, complete the email link with the private continuation code and registration password, sign in, and submit the legal document/declaration. An official User is created only after email proof. A Company and its first Owner are created only at complete legal submission.

The routes below return HTTP 410 with
`{status:410,message:"41030 ONBOARDING_RETIRED: Use shared registration and Company setup",data:null}`.
They do not bind old credentials, promote registrations, create profiles/Companies/documents
or send mail. Public registration routes remain anonymous; protected routes require
authentication and an active User. Malformed old request bodies still return 410.

The existing Candidate registration route and legacy Company DTO constructors remain compatible. Existing Candidate accounts are not automatically converted. New-applicant accounts cannot access Candidate profile/CV APIs. Opening onboarding settings enrolls an existing member into personal-proof gating; this is an opt-in side effect, not a claim that enrollment leaves their employer access unchanged. Members joining a Company with retained verification attempts must enroll before employer operations. Invitation acceptance itself remains available. Legacy Company rollout is not silently enabled for all historical members. An existing Owner with actual email proof can correct a rejected legacy dossier through the new UI and begin retained attempts from that correction; earlier URL-only evidence is not fabricated as a historical retained attempt.

Stored Company statuses remain PENDING/VERIFIED/REJECTED. Staff/Owner responses additionally expose `processingStatus` and `reviewOutcome`; CHECKED is derived and never substitutes for VERIFIED authorization.

## API surface

All JSON endpoints use the existing ApiResponse envelope. Secrets are request-body values, never query parameters.

| Method and path | Payload / purpose |
|---|---|
| POST `/api/employer-registrations` | `fullName`, Vietnamese `phone`, `companyName`, `gender` (MALE/FEMALE/OTHER/UNDISCLOSED), `workAddress`, `email`, `password`, `passwordConfirmation`. Returns attempt ID, continuation token and delivery acceptance. |
| POST `/api/employer-registrations/{id}/email/resend` | `continuationToken`; 60-second resend cooldown. |
| POST `/api/employer-registrations/{id}/email/verify` | `continuationToken`, `emailSecret`, original `password`; atomically promotes an attempt to one official User without automatic sign-in. |
| GET `/api/me/employer-onboarding` | Current personal proof, provisional information, stage and optimistic version. Existing members may enroll; existing Candidate-to-Company conversion is unavailable. |
| POST `/api/me/employer-onboarding/email/send` | Starts actual email proof for an existing Company member; no body. |
| POST `/api/me/employer-onboarding/email/verify` | `emailSecret` from the member confirmation link. |
| POST `/api/me/employer-onboarding/phone/send` | Retired: 410 after normal authentication; no body or side effects. |
| POST `/api/me/employer-onboarding/phone/verify` | Retired: 410 after normal authentication; no proof processing or writes. |
| PUT `/api/me/employer-onboarding/company` | `companyName`, `workAddress`, `expectedVersion`; actual-email-verified, unsubmitted applicant only. |
| POST `/api/me/employer-onboarding/documents` | Multipart `file`, max 10 MiB; private immutable PDF/JPEG/PNG content. |
| GET `/api/company-verification-documents/{id}` | Authenticated attachment, no-store/nosniff; uploader before submission, current Owner or Platform Staff afterward. |
| POST `/api/companies/verification-request` | New journey: `name`, `taxCode`, `legal` object described below. Separate Company email and arbitrary certificate URL are not accepted as legal evidence for enrolled accounts. |
| PUT `/api/companies/me/verification` or `/api/companies/{id}/resubmit` | Enhanced correction: `name`, `expectedVersion`, complete `legal`; only the same rejected Company, retaining MST and prior submissions. |
| GET `/api/companies/me/verification/history` | Owner's retained attempts, declarations, document identities, outcomes and reasons. |
| GET `/api/staff/companies/{id}/verification-history` | Same evidence for authenticated Platform Staff review. |

Exact retired JSON handlers return 410 before email proof, while active multipart
handlers retain the email prerequisite and current service authorization. Existing
Users, Companies, membership periods, jobs, private documents, declarations and review
decisions remain intact. Incomplete employer registrations are never automatically
promoted; their old links are inert. The person may explicitly use shared registration
subject to official User uniqueness. Historical URL-only evidence is not fabricated
as retained bytes. No data reset or new retention/deletion policy is introduced.

## Mail configuration and recovery

The existing Resend adapter serves requested account-email proof and Company decisions.
Configure environment-backed `ONBOARDING_EMAIL_FROM` and `ONBOARDING_EMAIL_CREDENTIAL`.
Never commit credentials. Account verification emails carry six-digit codes valid for
10 minutes with five failed attempts and a 60-second resend cooldown. The signed-in
client posts the code as `emailSecret`; no frontend URL or origin configuration is
needed by this adapter. `ONBOARDING_CONFIRMATION_URL` and
`ONBOARDING_ALLOWED_ORIGINS` are no longer used. Company invitation links retain
their separate `COMPANY_INVITATION_*` URL/origin settings and existing rules.
SMS/phone OTP runtime and `ONBOARDING_OTP_SECRET` remain removed; this journey needs
no SMS vendor. See [account-code rollout](unified-registration.md) for migration 85.

Staff decisions atomically retain the Company mutation, immutable decision, audit
and delivery intent. Mail targets the submitting User's current account email.
The separate worker uses `employer.onboarding.delivery-worker-enabled` (default true)
and `employer.onboarding.delivery-poll-ms` (default 30000). It makes up to eight
provider attempts with exponential backoff capped at one hour and a stable decision
idempotency key. Provider acceptance does not prove inbox delivery. Delivery failure
never reverses the committed decision; warning categories omit recipients and secrets.

| Variable / property | Purpose |
|---|---|
| ONBOARDING_EMAIL_FROM | Resend verified sender. |
| ONBOARDING_EMAIL_CREDENTIAL | Resend API credential. |
| ONBOARDING_CONFIRMATION_URL | HTTPS UI URL with no query/fragment. |
| ONBOARDING_ALLOWED_ORIGINS | Comma-separated exact HTTPS origins permitted for that link. |
| employer.onboarding.delivery-worker-enabled | Defaults true; disable in tests or when intentionally stopping decision delivery. |
| employer.onboarding.delivery-poll-ms | Defaults 30000 milliseconds. |

Email proof expires after 24 hours. A new proof replaces the old one. New registrations are capped at five attempts per email per hour; individual sends use a 60-second cooldown.

SMS verification endpoints return 410 without calling delivery. The unused delivery interface remains for compatibility; no OTP secret or SMS provider is required.

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

## Verification

`V2026.10.18.73__employer_onboarding.sql` adds pending registration, personal state, private document, retained attempt and decision-delivery tables. It preserves official User email uniqueness and originally added a partial unique index for verified phone; that index now protects frozen history only. `V2026.10.27.83__canonical_account_phone.sql` adds the canonical User contact; follow the [required cutover](canonical-account-phone.md#controlled-cutover). Initial Company/Owner/attempt creation and decision/audit/delivery-intent changes are transactional.

Existing migration files are unchanged. Historical auto-verified email flags are not backfilled as actual proof. Existing Company contact emails and legacy URL-only dossiers remain historical; they are not fabricated as retained document bytes.

Database guards reject document mutation/deletion and changes to submitted snapshots/completed decisions. A pending attempt permits only its one review decision. Exact retention periods, abandoned-registration cleanup and legally reviewed deletion/disposal remain unresolved; approve a controlled policy before production release rather than interpreting these guards as indefinite-retention approval.

Two narrow existing Candidate persistence defects were exposed by the ordinary-Candidate control in the new HTTP tests: manually assigning an ID configured for Hibernate UUID generation, and binding a converted JSON string to a PostgreSQL jsonb column. Removing the manual assignment and applying an explicit jsonb write cast restore actual profile persistence without a schema change.

## Verification and remaining integration

Use the isolated PostgreSQL setup in [testing.md](testing.md). Focused commands from `backend/`:

```bash
./mvnw -Dtest=EmployerOnboardingHttpTest,OnboardingSecretsTest,VerificationResponseContractTest test
./mvnw test
./mvnw package
```

Tests cover provisional registration, wrong credentials, competing claims, atomic email promotion, direct-request capability gates, canonical phone conflicts, retired SMS endpoints, private documents, rejected correction, immutable evidence, actual Staff actor attribution and ordinary Candidate profile persistence. Test-only delivery fakes do not imply actual external delivery is configured.

Before a production rollout: complete the separately scoped immediate-account/email-only journey, configure Resend and link origins, approve retention/deletion and aggregate abuse limits, finalize the legacy-member migration/window and support contact, and integrate job search/view. The repo does not implement job discovery; this change does not fabricate that separate subsystem. Browser interaction/accessibility and actual provider delivery require environment-backed verification in addition to API tests.

### Verification checkpoint — 2026-10-18

- Full backend suite: 183 tests, zero failures/errors/skips, using the isolated PostgreSQL test service.
- `./mvnw -DskipTests package`: passed after the full suite. The packaged JAR contains all three frontend assets, the declaration resource and the new Flyway migration.
- `node --check frontend/app.js`, frontend DOM-reference checks, documentation-link checks and `git diff --check`: passed.
- Final Standards and Spec/security reviews: no remaining actionable findings after corrections.
- Actual SMS/provider delivery and interactive browser/accessibility testing were not performed. No SMS vendor was selected, no real delivery credentials were requested in chat, and no browser automation dependency was installed. These checks remain explicit handoff items.
