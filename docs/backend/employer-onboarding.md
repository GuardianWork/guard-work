# Employer onboarding implementation

Implements the new-applicant path in [GW-SR-REC-03](../software-requirements/rec/company-onboarding-verification-enhancement-spec.md), using the [confirmed technical contract](../software-requirements/rec/company-onboarding-implementation-contract.md) and [ADR 0005](../architecture/decisions/0005-staged-employer-onboarding.md).

## Journey and compatibility

Open `/` on the backend origin for the Vietnamese onboarding interface. Register personal details and provisional Company information, complete the email link with the private continuation code and registration password, sign in, verify the phone, and submit the legal document/declaration. An official User is created only after email proof. A Company and its first Owner are created only at complete legal submission.

Pending attempts do not reserve email permanently. Each attempt has its own private continuation proof. On the same browser tab the UI keeps that continuation in sessionStorage; on another device the applicant must supply the code saved during registration. Email GET links do not consume proof, and URL-fragment secrets are removed from browser history after loading. Session tokens remain in memory and are lost on reload.

The existing Candidate registration route and legacy Company DTO constructors remain compatible. Existing Candidate accounts are not automatically converted. New-applicant accounts cannot access Candidate profile/CV APIs. Opening onboarding settings enrolls an existing member into personal-proof gating; this is an opt-in side effect, not a claim that enrollment leaves their employer access unchanged. Members joining a Company with retained verification attempts must enroll before employer operations. Invitation acceptance itself remains available. Legacy Company rollout is not silently enabled for all historical members. A personally verified existing Owner can correct a rejected legacy dossier through the new UI and begin retained attempts from that correction; earlier URL-only evidence is not fabricated as a historical retained attempt.

Stored Company statuses remain PENDING/VERIFIED/REJECTED. Admin/Owner responses additionally expose `processingStatus` and `reviewOutcome`; CHECKED is derived and never substitutes for VERIFIED authorization.

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
| POST `/api/me/employer-onboarding/phone/send` | `phone`; 60-second cooldown. Unconfigured or failed SMS returns 503 and cannot produce valid delivered proof. |
| POST `/api/me/employer-onboarding/phone/verify` | Six-digit `code`; 5-minute expiry, at most 5 incorrect attempts. Failed-attempt counts commit before returning an error. |
| PUT `/api/me/employer-onboarding/company` | `companyName`, `workAddress`, `expectedVersion`; phone-verified, unsubmitted applicant only. |
| POST `/api/me/employer-onboarding/documents` | Multipart `file`, max 10 MiB; private immutable PDF/JPEG/PNG content. |
| GET `/api/company-verification-documents/{id}` | Authenticated attachment, no-store/nosniff; uploader before submission, current Owner or Platform Admin afterward. |
| POST `/api/companies/verification-request` | New journey: `name`, `taxCode`, `legal` object described below. Separate Company email and arbitrary certificate URL are not accepted as legal evidence for enrolled accounts. |
| PUT `/api/companies/me/verification` or `/api/companies/{id}/resubmit` | Enhanced correction: `name`, `expectedVersion`, complete `legal`; only the same rejected Company, retaining MST and prior submissions. |
| GET `/api/companies/me/verification/history` | Owner's retained attempts, declarations, document identities, outcomes and reasons. |
| GET `/api/admin/companies/{id}/verification-history` | Same evidence for authenticated Platform Admin review. |

`legal` contains `workAddress`, `submitterPosition`, `representation` (LEGAL_REPRESENTATIVE or AUTHORIZED_REPRESENTATIVE), `declarationAccepted: true`, and the uploaded `documentId`. A retained declaration is not independent proof of representation; no authorization-letter upload is required. Documents use basic byte-signature validation, not full malware or forensic inspection.

Existing Admin decision endpoints retain pending-only/expectedVersion/reason constraints. When a real authenticated principal exists, the audit actor/role comes from that principal, overriding legacy X-Admin headers. Header-only access remains blocked by the real SecurityFilterChain. The legacy standalone controller-test seam is retained.

## Configuration and delivery

Use environment variables; never put real credentials in tracked files.

| Variable / property | Purpose |
|---|---|
| ONBOARDING_EMAIL_FROM | Resend verified sender. |
| ONBOARDING_EMAIL_CREDENTIAL | Resend API credential. |
| ONBOARDING_CONFIRMATION_URL | HTTPS UI URL with no query/fragment. |
| ONBOARDING_ALLOWED_ORIGINS | Comma-separated exact HTTPS origins permitted for that link. |
| ONBOARDING_OTP_SECRET | Secret used for account/phone-bound HMAC of low-entropy OTP; use at least 32 random bytes. Missing secret fails closed. |
| employer.onboarding.delivery-worker-enabled | Defaults true; disable in tests or when intentionally stopping decision delivery. |
| employer.onboarding.delivery-poll-ms | Defaults 30000 milliseconds. |

Email proof expires after 24 hours. A new proof replaces the old one. New registrations are capped at five attempts per email per hour; individual sends use a 60-second cooldown. Broader SMS budget/rate-limit policy remains a production-readiness item.

**SMS is not configured by this implementation.** The approved contract left the vendor unspecified. `OnboardingDelivery.sms` currently returns false in the production adapter; provider selection and its concrete HTTP integration are still required for real phone verification. No production fake, console OTP, or verification bypass is provided. HTTP tests substitute a mock sender and prove the remainder of the journey.

Decision delivery is scoped to enhanced dossier attempts. A committed decision writes an intent; a worker attempts up to eight provider submissions with exponential backoff capped at one hour. A stable idempotency key identifies the decision. A provider-accepted email sets `delivered_at`; this is provider acceptance, not proof of mailbox arrival. Delivery states/attempts remain in `company_decision_deliveries` for operator inspection. Missing credentials exhaust retries without reversing the decision; operators must configure delivery before enabling it for actual users.

The worker emits fixed WARN events `company_decision_email/retry_scheduled` and `company_decision_email/retry_exhausted` without recipients, provider bodies or secret values. After fixing delivery configuration, an authorized database operator can inspect exhausted records and explicitly requeue a selected decision (never rerun the business approval):

```sql
SELECT id, attempts, next_attempt_at FROM company_decision_deliveries
WHERE delivered_at IS NULL AND attempts >= 8;

-- Bind :decision_id to the reviewed decision UUID using the operator's database client.
UPDATE company_decision_deliveries
SET attempts = 0, next_attempt_at = CURRENT_TIMESTAMP, version = version + 1
WHERE id = :decision_id AND delivered_at IS NULL AND attempts >= 8;
```

Use the same decision ID for provider idempotency. Provider retention of idempotency keys is finite; manual requeue does not promise exactly-once mailbox delivery. The worker's polling lifecycle is scoped and does not claim request correlation/completion behavior for scheduled work.

## Data and migrations

`V2026.10.18.73__employer_onboarding.sql` adds pending registration, personal state, private document, retained attempt and decision-delivery tables. It preserves official User email uniqueness and adds a partial unique index for verified normalized phone. Initial Company/Owner/attempt creation and decision/audit/delivery-intent changes are transactional.

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

Tests cover provisional registration, wrong credentials, competing claims, atomic email promotion, direct-request capability gates, phone attempt exhaustion, failed delivery, private documents, rejected correction, immutable evidence, actual Admin actor attribution and ordinary Candidate profile persistence. Test-only delivery fakes do not imply actual external delivery is configured.

Before a production rollout: choose/integrate the SMS provider, configure Resend and link origins, approve retention/deletion and aggregate abuse limits, finalize the legacy-member migration/window and support contact, and integrate job search/view. The repo does not implement job discovery; this change does not fabricate that separate subsystem. Browser interaction/accessibility and actual provider delivery require environment-backed verification in addition to API tests.

### Verification checkpoint — 2026-10-18

- Full backend suite: 183 tests, zero failures/errors/skips, using the isolated PostgreSQL test service.
- `./mvnw -DskipTests package`: passed after the full suite. The packaged JAR contains all three frontend assets, the declaration resource and the new Flyway migration.
- `node --check frontend/app.js`, frontend DOM-reference checks, documentation-link checks and `git diff --check`: passed.
- Final Standards and Spec/security reviews: no remaining actionable findings after corrections.
- Actual SMS/provider delivery and interactive browser/accessibility testing were not performed. No SMS vendor was selected, no real delivery credentials were requested in chat, and no browser automation dependency was installed. These checks remain explicit handoff items.
