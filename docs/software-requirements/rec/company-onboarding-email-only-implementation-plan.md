# [GW-PLAN-REC-03-EMAIL-ONLY] Gap Analysis and Next Implementation Plan

> Implementation update (2026-10-27): after the canonical-phone delivery, the user approved the remaining concrete schema/API in plan sections 3–5. Immediate official accounts, bound authenticated email proof, normalized-email uniqueness/preflight, scoped pre-email restrictions, invitation admission checks and pending-route 410 retirement are implemented. See [the current backend contract/cutover](../../backend/employer-onboarding.md). Tables below preserve the original planning inventory; they are not a claim that the implemented scope remains awaiting approval.


| Field | Value |
|---|---|
| Plan ID | GW-PLAN-REC-03-EMAIL-ONLY |
| Specification | [GW-SR-REC-03](company-onboarding-verification-enhancement-spec.md) |
| Date / Last updated | 2026-10-27 |
| Owner | VyTrg (proposed, following REC-03 ownership) |
| Human approver | VyTrg (proposed reviewer; review-register reconciliation pending) |
| Status | Draft technical proposal; interview decisions Q10–Q17 confirmed by the user |
| Evidence boundary | Checked-out source, migrations, test definitions and build metadata; no live database or deployed client inspected; no runtime tests executed |

## 1. Scope and authorization

The user confirmed the product journey in Q1–Q5/Q7–Q9, then agreed to every recommendation in Q10–Q13 and Q14–Q17. These confirmations authorize the directions below. They do not claim the proposed exact schema/API has been implemented or independently set a specification to Approved. The next implementation requires review of this concrete technical proposal under the root Agent Guide.

| Decision | Confirmed direction |
|---|---|
| Q10 | Implement the backend journey, API contract and HTTP request examples; no new frontend in this release. |
| Q11 | One canonical normalized phone per User, shared by Candidate and employer; nullable for existing phone-less accounts and required for new employer registration. |
| Q12 | Coordinate backend/client contract changes in one release; do not maintain two active registration journeys indefinitely. Preserve the response envelope. |
| Q13 | Keep a successfully created account when email submission fails; report delivery acceptance separately and allow restricted sign-in/resend. |
| Q14 | Retain old pending attempts for reference, require fresh registration, and retire old registration-proof/SMS endpoints with 410. Never bulk-promote or transfer another attempt's data. |
| Q15 | Block conversion on unresolved cross-account phone collisions, differing phones for one User or invalid values. Preserve phone-less Users; never select a winner automatically. |
| Q16 | Existing eligible Candidate profile updates may change/clear the shared account phone. Return canonical +84 values, enforce mobile validation/uniqueness and preserve authorization. No new employer phone-change API. |
| Q17 | Email confirmation requires sign-in as the correct User, works across devices without the old continuation token, and does not itself create a session. |

Q6 (already-ACTIVE Recruiter to new-Company Owner) remains **—; deferred**. No new departure, transfer or multi-membership behavior is selected. Current database/membership constraints remain unchanged.

Non-goals: new UI/frontend framework, new shared auth/notification architecture, new dependency/test infrastructure, Candidate-to-employer conversion, production support console, production retention policy and changes to Company review or Job permissions.

## 2. Evidence-backed gap matrix

The source paths below are relative to `backend/src/main/java/com/guardwork/backend/` unless otherwise linked. Requirement IDs refer to REC-03, not similarly numbered entries in REC-01/02.

| Gap | Current evidence | Required change | Trace / priority |
|---|---|---|---|
| E01: account timing | [EmployerRegistrationService.begin/verify](../../../backend/src/main/java/com/guardwork/backend/onboarding/service/EmployerRegistrationService.java) saves pending attempt first and creates User/profile at email promotion. | Create User + employer profile + shared phone + challenge atomically during registration. No Company/membership yet. | REQ-REC-01/17; P0 |
| E02: restricted login | [AuthService.login/refreshToken](../../../backend/src/main/java/com/guardwork/backend/auth/AuthService.java) already accept ACTIVE User without checking email. | Reuse login/portal/token behavior. New applicants become able to sign in because a User now exists; do not add an email-before-login rule. | REQ-REC-03/04; P0 |
| E03: overly broad exemptions | [OnboardingAccessPolicy](../../../backend/src/main/java/com/guardwork/backend/onboarding/service/OnboardingAccessPolicy.java) allows broad auth/onboarding, Company, notifications and invitation prefixes; [OnboardingWebConfiguration](../../../backend/src/main/java/com/guardwork/backend/onboarding/controller/OnboardingWebConfiguration.java) enforces it as an MVC interceptor. | Stage-and-method-aware rules for applicable Users, plus service guards before mutations. Pre-email access is shell/auth recovery/proof only. | REQ-REC-04/13/14; P0 |
| E04: proof/enrollment | [PersonalVerificationService](../../../backend/src/main/java/com/guardwork/backend/onboarding/service/PersonalVerificationService.java) already hashes and consumes authenticated email proof, but has no challenged-email snapshot. Its state GET may enroll existing members and rejects a no-membership User without a profile. | Create new applicant profile at registration; bind challenge and completed proof to exact email; preserve scoped existing-member enrollment and locking. | REQ-REC-02/18; P0 |
| E05: SMS in two boundaries | `requirePersonal()` gates draft edits/uploads/legal transactions; access policy and state independently require phone. Production `sms()` returns false. | Remove phone gating everywhere, retire active OTP code/configuration, retain historical proof/challenge columns and records. | REQ-REC-05/06/07/13/18; P0 |
| E06: no global phone boundary | User has no phone. Employer has a verified-only partial unique index; [CandidateProfileService.updateProfile](../../../backend/src/main/java/com/guardwork/backend/candidate/CandidateProfileService.java) stores raw phone independently. | One normalized User phone, database uniqueness and all-write-path coordination. Separate service existence checks are insufficient for races. | REQ-REC-17/19; P0 |
| E07: Candidate contract | [UpdateCandidateProfileRequest](../../../backend/src/main/java/com/guardwork/backend/candidate/dto/UpdateCandidateProfileRequest.java) accepts broader prefixes; [controller mapping](../../../backend/src/main/java/com/guardwork/backend/candidate/CandidateProfileController.java) reads the profile column. | Read canonical User phone, write/clear it atomically with profile updates, return +84, and tighten mobile validation intentionally. Preserve the rest of the DTO/envelope. | REQ-REC-19; P0 |
| E08: admission before email proof | [RecipientInvitationService.accept](../../../backend/src/main/java/com/guardwork/backend/invitation/service/RecipientInvitationService.java) has recipient/secret/Company/membership locks and historical receipts, but no actual-email prerequisite. | Guard new scoped admission before side effects without changing ordinary legacy invitation scope or re-admitting historical receipts. | REQ-REC-14; P0 |
| E09: atomic Owner flow | [LegalDossierService.register/correct](../../../backend/src/main/java/com/guardwork/backend/onboarding/service/LegalDossierService.java) composes retained attempts with existing Company/Owner transaction. | Reuse atomicity, evidence access, MST/version and Staff safeguards; change proof prerequisite. | REQ-REC-07–12/15/16; preserve |
| E10: obsolete public contract | [EmployerOnboardingController](../../../backend/src/main/java/com/guardwork/backend/onboarding/controller/EmployerOnboardingController.java) returns attempt UUID/continuation and exposes old proof/phone actions; SecurityConfig permits old registration paths. | Replace registration response and retire specified endpoints with deterministic 410; no pending promotion. | REQ-REC-20/21; P0 |
| E11: data/fixtures | [V2026.10.18.73](../../../backend/src/main/resources/db/migration/V2026.10.18.73__employer_onboarding.sql) and Candidate migration store independent phones. [seed.sql](../../../backend/docker/dev-seed/seed.sql) writes local-format phones, including synthetic verified employer fixtures. | Preflight/backfill/new migration; normalize seed input and write canonical phone. Fresh local fixtures need no synthetic phone proof; preserve existing fixture state and label synthetic email proof as local-only. Preserve committed migration history and evidence. | Q11/Q15/Q16; P0 |
| E12: outdated test expectations | [EmployerOnboardingHttpTest](../../../backend/src/test/java/com/guardwork/backend/company/EmployerOnboardingHttpTest.java) asserts no User/pre-proof login and competing unverified claims; Candidate tests expect raw phones. | Replace superseded expectations; preserve legal evidence, moderation, rollback, portal and ordinary Candidate controls. | REC-03 section 9; P0 |
| E13: source UI absent | No source `frontend/` exists in this checkout, although `pom.xml` declares it as a resource and historical docs describe UI delivery. Generated files under `target/` are not source. | Correct documentation; deliver backend contract/request examples only. External client and browser work remain separate. | Q10; scope |
| E14: stale request samples | [company-onboarding.http](../../../backend/http/company-onboarding.http) uses Candidate registration, legacy dossier fields and old Platform ADMIN routes. | Provide the actual new employer registration/proof/dossier/invitation/Staff flow with placeholders; do not expose real tokens or proof material. | Q10; P1 |

## 3. Concrete schema and migration proposal

This is the proposed physical design for review, not an applied migration. Use a new migration following the current README convention and choose its exact version after checking the branch's latest migration. Existing Flyway files are immutable.

| Area | Proposed change |
|---|---|
| Canonical phone | Add nullable `users.phone_number VARCHAR(20)`, UNIQUE across non-null values, with a CHECK allowing only `+84[35789][0-9]{8}` when present. A phone-less Candidate/Staff/legacy User remains valid. New employer registration requires a non-null value at the service boundary. |
| Email ownership | Preserve `users.email` and existing uniqueness. Add a unique index on `lower(btrim(email))` after email-collision preflight, covering manual/legacy case variants without rewriting stored email or evidence. Runtime writers retain trim/lowercase normalization. |
| Challenge binding | Add nullable `employer_profiles.email_challenge_email VARCHAR(255)` and `email_verified_email VARCHAR(255)`. Reuse existing hash/expiry/sent fields and pessimistic locking/version. Issuance snapshots normalized current email; confirmation compares actor, snapshot, current email, hash and expiry. Completion records verified-email snapshot and timestamp, then clears challenge hash/snapshot. |
| Old actual email proof | For existing profiles with actual `email_verified_at`, preserve that timestamp and bind the snapshot to the linked account email during controlled migration. Never derive actual proof from `users.email_verified=true` alone. Investigate known historical email changes rather than guessing. |
| Old phone columns | Keep `candidate_profiles.phone_number`, `employer_profiles.phone` and phone-proof/challenge fields as legacy data. Do not use them as current contact/eligibility sources, dual-write them or expose old proof as verification of the new canonical number. No column drop or evidence deletion in this release. |
| Old pending attempts | Keep `employer_registrations` and its rows intact but inactive. New registration/proof must not write/promote them; retired handlers must not look up or reveal their contents. |
| Company data | No membership constraint, Company state, dossier storage, declaration, audit or retained-attempt schema change is proposed. |

The existing verified-phone partial index may remain on the frozen historical column; it does not enforce current global ownership. All new/current contact reads and writes must use User phone. New employer profiles leave legacy `phone` and phone-proof fields NULL; new Candidate profiles do not populate the legacy phone column. Otherwise the preserved historical index can incorrectly block reuse of a number already released from the canonical source. A shared phone normalizer can live narrowly in the existing User feature; this requires no validation framework or new dependency.

### 3.1 Preflight and backfill

Run a read-only inventory first, then recheck under the stopped-writer cutover before backfill/constraints. Supply a reviewable SQL report/script with implementation; do not claim this planning task inspected live data.

1. Collect Candidate and employer phones by User; normalize trimmed local/+84 equivalents using the agreed mobile syntax. `NULL` means absent. Blank/invalid non-null values require review, not silent deletion.
2. One User with repeated representations of the same normalized phone is one contact. One User with multiple distinct numbers blocks conversion until reviewed. Users with neither value retain NULL.
3. A normalized phone shared by distinct Users blocks conversion, regardless of old phone verification or account age. Keep pending attempts in a separate informational category because they are not official accounts and are retired by Q14.
4. Group official emails by trim/lowercase; collisions block the normalized-email index. Do not merge Users, choose a winner, erase contacts or reset the database.
5. Produce an access-controlled operator report; do not put full contact values or private pending-registration data in application logs.
6. After reviewed corrections and a clean recheck, backfill one normalized `users.phone_number`, bind existing genuine email proof snapshots, add constraints and deploy the new writers together. Migration must fail if unresolved inconsistencies remain; no partial automatic conversion.

Candidate GET must assemble contact data from the User source. Candidate PUT updates User phone and profile in the same transaction, allows NULL as before, and translates uniqueness races to a safe 409 without saving other profile changes. Do not mutate a legacy phone field merely to populate a response. No new employer phone-change/recovery endpoint is included.

## 4. Concrete API proposal

Preserve `{status, message, data}` and existing public/Staff portal contracts. This section proposes exact changes for final technical review. It adds no global error architecture or automatic authenticated session at registration/email confirmation.

### 4.1 Registration

`POST /api/employer-registrations` retains the eight current inputs: `fullName`, `phone`, `companyName`, `gender`, `workAddress`, `email`, `password`, `passwordConfirmation`. Validation/password/full-name/opaque-username conventions remain unless needed for the confirmed phone rule.

Proposed HTTP 201 and body:

```json
{
  "status": 201,
  "message": "Account created; verify your email",
  "data": {
    "userId": 123,
    "emailVerified": false,
    "stage": "EMAIL_REQUIRED",
    "deliveryAccepted": true
  }
}
```

Create ACTIVE/USER, canonical phone, applicant-origin EmployerProfile, provisional Company data and account-bound email challenge in one transaction. Start email submission only after commit. A timeout/rejection/unconfigured sender changes `deliveryAccepted` to false while HTTP 201 still records successful account creation. True means confirmed provider acceptance, not mailbox arrival; false includes unconfirmed acceptance after a timeout and does not prove non-delivery. Never return secrets, password hashes, a continuation token, or an access/refresh token.

Duplicate normalized email/phone returns safe HTTP 409 using the scoped identifier-unavailable convention; never report the other account's verification/profile/Company. Database constraints decide races. A duplicate retry does not implicitly resend mail or create another User; the client uses existing login/recovery and authenticated resend. Unexpected transaction failure leaves no User/profile fragment.

The old five-pending-attempts-per-email/hour query must not become an active dependency on the retired table. Under immediate uniqueness only one official registration can succeed for an email; duplicate submissions cause no new mail. Keep existing email resend cooldown and document broader aggregate limits as production follow-up.

### 4.2 Authenticated email and state

Reuse login/refresh/logout and `/api/auth/me`; no new login algorithm, portal claim, token storage or auth-me field is required. Sign-in remains an explicit client action after registration.

| Operation | Proposed contract |
|---|---|
| `GET /api/me/employer-onboarding` | Preserve current identity/provisional/version/origin/role fields; `phone` reads User phone, `emailVerified` reflects actual matching proof. Remove current `phoneVerified` from this scoped response. Replace PHONE_REQUIRED/LEGAL_REQUIRED with the stages below. |
| `POST /api/me/employer-onboarding/email/send` | Authenticated; no request body; 200 with `data.deliveryAccepted` boolean, existing 60-second cooldown. Prepare challenge before after-commit provider submission. Already-verified behavior remains the existing scoped validation response. |
| `POST /api/me/employer-onboarding/email/verify` | Authenticated; `{ "emailSecret": "<from email link>" }`; 200 with null data on successful single-use confirmation. Wrong account/email, expired/replaced/consumed proof fails under existing scoped invalid-proof handling. No token issuance or continuation/password from the retired journey. |
| Email link | Use the existing allow-listed HTTPS confirmation URL and URL-fragment secret convention. The external client asks for sign-in as needed and POSTs proof; a GET/email preview never consumes it. Keep tokens/fragments out of logs. |

Proposed stages: `EMAIL_REQUIRED`; `COMPANY_ACTION_REQUIRED` for an email-verified applicant without membership; otherwise the current Company's `PENDING`, `REJECTED` or `VERIFIED` after proof. Existing suspended/unauthorized requests still fail under current checks. `applicant` remains the existing origin flag and must not be treated as a Company role.

Actual proof requires both matching verified-email snapshot and timestamp; the legacy User boolean is compatibility data. New successful proof may synchronize that boolean, but it is never sufficient by itself. Scope remains new applicants/already-enrolled accounts and the existing enhanced-membership operation gates; do not newly enroll unrelated legacy accounts or change ordinary legacy invitation acceptance globally.

### 4.3 Retired routes

| Route | Target |
|---|---|
| `POST /api/employer-registrations/{id}/email/resend` | 410; register/login/resend using the new journey. |
| `POST /api/employer-registrations/{id}/email/verify` | 410; no promotion even for a once-valid old link. |
| `POST /api/me/employer-onboarding/phone/send` | 410 after normal authentication; no enrollment, delivery or profile write. |
| `POST /api/me/employer-onboarding/phone/verify` | 410 after normal authentication; no verification bypass or profile write. |

Use a generic scoped `41030 ONBOARDING_FLOW_RETIRED` response with envelope status 410, independent of old record existence/content. Preserve normal route method/authentication handling. Retirement-only handlers may be reached for applicable authenticated accounts without granting any onboarding action. Remove active SMS sender/OTP methods and OTP-only environment requirements; keep old migration/data intact.

## 5. Authorization and legal/invitation integration

Keep the current MVC interceptor and narrow scoped service checks. Replace broad prefix exemptions with an explicit stage/method matrix; do not invent a new platform authorization architecture.

| State | Allowed scoped actions | Required denials |
|---|---|---|
| Email unverified | Own `/auth/me`, onboarding state, email send/confirm; existing login/refresh/logout and necessary password recovery/change actions | Provisional edits, uploads/downloads, dossier/status/history operations, invitation preview/accept/decline, notifications and recruitment/Candidate effects. Terminal retired routes only return 410. |
| Email verified, no membership | Draft edits, own upload access, initial complete dossier submission and own eligible invitation actions | Recruitment, unrelated documents and Candidate profile/CV/Application effects for the applicant origin. |
| Owner, PENDING | Current Owner status/history/private evidence | Recruitment, correction-before-rejection and invitation issue/resend. |
| Owner, REJECTED | Current Owner feedback/history/private evidence, uploads and complete correction/resubmission | Recruitment and invitation issue/resend. |
| VERIFIED Company | Existing member-role/operation checks plus actual email proof where scoped | Any action beyond current permission, active membership, User/Company status or data visibility. |

Public unauthenticated discovery/auth flows retain their current semantics. STAFF retains platform moderation authority. The shell itself is an external client responsibility; backend state/denials support it without claiming an in-repo screen exists.

New applicants already have an EmployerProfile before any state/proof call. Keep intentional legacy-member enrollment scoped as documented; do not make every legacy state read globally eligible. Existing historical-email booleans are not actual proof.

Before new scoped invitation activation, require actual email proof inside the admission transaction as well as request eligibility. Determine that scope from existing applicant/enrollment state; do not call an enrolling/require-personal helper on every legacy invitee. Place the new-activation service guard after historical receipt handling and before new membership/audit/notification effects. Preserve exact recipient/secret, expiry/revocation/Company eligibility, one-active-membership constraints, audit and notifications. Historical acceptance receipts must never create a new membership; preserve existing receipt/retry behavior for eligible callers, while an applicable unverified caller cannot bypass the pre-email request gate. Ordinary un-enrolled legacy recipients retain existing compatibility; broadening them is outside the confirmed rollout.

Reuse LegalDossierService's complete-document/declaration and atomic Company/Owner/attempt transaction. Replace the personal phone prerequisite with actual matching email proof at draft/upload/submission/correction boundaries. Owner is ACTIVE at successful submission while Company remains PENDING. Keep rejected-only correction, immutable MST/Company ID, Staff-only decisions, reason length/version constraints, private evidence and after-commit decision email.

## 6. Ordered implementation packages

These are reviewable commits/work packages, not authorization to commit or partial-release claims. No Git commit/push is authorized by this plan. P0 packages form one coherent backend/client cutover; do not expose immediate unverified Users before their gates are in place.

| Package | Scope / primary files | Prerequisite | Done when |
|---|---|---|---|
| I0: technical review and inventory | Review this contract; prepare read-only phone/email preflight and migration fixtures | Human review of proposed physical/API changes | Approved technical boundaries; source-data conflicts classified/resolved for target environment; Q6 still deferred. |
| I1: canonical identity | New migration; User mapping/repository/phone normalizer; Candidate service/DTO mapping/validation; seed SQL | I0 | Canonical nullable phone and normalized email uniqueness; same-User aliases handled, conflicting data blocks migration, all Candidate contact writes atomic. |
| I2: immediate registration and restrictions | EmployerRegistrationService, onboarding DTO/controller, scoped policy/interceptor and service guards | I1; deliver with I3 before cutover | User/profile/challenge created atomically, email-unverified sign-in works, forbidden requests have no effects, duplicate/race handling is safe. |
| I3: email-only proof and integrations | PersonalVerificationService, delivery adapter, LegalDossierService, RecipientInvitationService, state DTO and applicable tests | I2 and agreed proof/state contract | Account/email-bound authenticated proof; no phone gate; valid scoped invitation activates Recruiter; dossier creates Owner atomically; already-enrolled accounts proceed without SMS. |
| I4: retirement and client handoff | Old endpoint handlers/security matchers, delivery interface, active OTP config, HTTP examples and backend docs | I2/I3 | Deterministic 410, no active pending promotion/SMS, old records retained, client examples match the proposed 201/proof/state contract. No reconstruction from generated UI assets. |
| I5: full verification and coordinated release | Existing isolated PostgreSQL tests, packaging, rollout handoff | I1–I4 | Required tests/build pass, migration/client impacts documented, external frontend integration remains explicit, no newly imposed legacy gates. |

## 7. Verification matrix

Use existing unit, real HTTP/security and isolated PostgreSQL migration/concurrency boundaries. Do not install a new test framework, add mocked-JDBC “integration” claims or run tests against developer data.

| Boundary | Required proof |
|---|---|
| Registration | One User/profile created immediately; unverified proof state; no membership/Company; input validation; 409 for verified/unverified existing identifiers; no partial records on profile/constraint failure. |
| Identity races | Employer vs employer registration; Candidate update vs employer registration; Candidate vs Candidate update; local/+84 aliases; normalized email variants; same User retaining its own number; each losing transaction rolls back all related changes. |
| Candidate compatibility | Phone-null registration/skeleton preserved; GET reads canonical contact; PUT normalizes/changes/clears canonical phone; conflict rolls back profile changes; old historical proof does not verify a new phone; no broadened Candidate access. |
| Migration | Same-User aliases backfill once; phone-less Users remain; invalid phones, multiple distinct same-User contacts, cross-User collisions and normalized-email duplicates block conversion without destructive edits. Existing actual-email proof preserved; auto-verified User flag alone not upgraded to proof. |
| Restricted requests | Actual SecurityFilterChain plus interceptor tests for permitted auth/state/proof and denied legal/invitation/notification/Candidate/recruitment calls before side effects. Check old access tokens and refresh behavior, STAFF/portal controls and unrelated legacy controls. |
| Email proof | Actor/email snapshot binding; 24-hour expiry; 60-second resend; replacement/single use; concurrent resend/confirm; wrong-account/consumed proof; cross-device authenticated confirmation; no session from link GET or confirmation. |
| Delivery | Registration persists and reports false on provider rejection/timeout/config failure; resend works after cooldown; no secret leakage; no duplicate-registration-triggered resend. Preserve committed decision delivery behavior and retry tests. |
| Invitation | Scoped unverified activation denied without membership/audit/notification effects; verified valid recipient admitted once; wrong recipient/revoked/expired/Company suspension behavior retained; retries do not re-admit departed/removed members. |
| Legal and moderation | No SMS setup needed for edit/upload/submission/correction; complete dossier/declaration still required; atomic PENDING Company/ACTIVE OWNER/attempt; duplicate MST and audit failure roll back; history/privacy/rejection/version/Staff attribution preserved. |
| Retirement and rollout | Old pending/phone routes return agreed 410 after applicable auth without data lookup/write; retired pending records never promote; already-enrolled email-proved accounts proceed without SMS; no newly gated unrelated legacy accounts. |

Amend `EmployerOnboardingHttpTest` rather than retaining assertions that no User exists before proof, pending claims do not reserve email or only verified phone owners are unique. Preserve its retained-evidence/moderation checks. Amend Candidate controller/service tests intentionally for mobile-only +84/NULL behavior. Extend `RecipientInvitationHttpTest`; preserve its concurrency, receipts, audit and notifications controls. Reuse `OnboardingSecretsTest` phone/secret coverage and remove active OTP-only scenarios alongside route retirement.

Run from `backend/` during implementation:

```bash
docker compose -f docker-compose.test.yml up -d --wait
./mvnw -Dtest=EmployerOnboardingHttpTest,OnboardingSecretsTest,RecipientInvitationHttpTest,CandidateProfileServiceTest,CandidateProfileControllerTest test
./mvnw test
./mvnw package
git diff --check
```

Add the new migration/concurrency test classes to the focused run once their names are chosen. Full tests and package are required because the implementation includes schema/configuration changes. Manual/API examples supplement automated proof; live email, external client and browser checks require an environment and do not follow from provider fakes. This planning task ran none of the backend commands above.

## 8. Cutover, risks and remaining boundaries

1. Coordinate client release and announce that outstanding old registration links require fresh registration. Keep existing Users, passwords, memberships, Companies, actual proof and retained evidence.
2. Stop old application/seed/contact writers before the final inventory and migration. Resolve reported inconsistencies; do not bypass checks or erase data to make deployment pass.
3. Apply the new migration and start the backend with I1–I4 together. Configure existing email sender/allowed confirmation origin; remove the active OTP-secret requirement. The absence of frontend source does not authorize removing unrelated build configuration or changing token-storage architecture.
4. Verify new registration/restricted sign-in/email proof, both admission branches, Candidate phone updates, existing enrolled accounts and 410 routes. Do not run fixtures on production or claim test mail proves real delivery.
5. Old binaries write independent contacts and require the superseded journey; they are not safe concurrent writers after cutover. Plan a forward fix or an explicitly reviewed restore rather than editing Flyway history or casually reverting only application code.

Manual phone dispute support, controlled retention/disposal, declaration/legal review, support contact and aggregate abuse policy remain production readiness items. They are not implicitly implemented by canonical uniqueness. Q6 stays undecided. External UI/client integration is a separate delivery dependency even though its coordinated contract upgrade is agreed.

This document is the current gap/work-order proposal. The [earlier gap analysis](company-onboarding-verification-gap-analysis.md) retains a 2026-10-18 historical snapshot and must not drive new SMS/provider or pending-promotion implementation work.
