# GW-SR-REC-03 — Implementation Contract and Required Amendment

> Unified registration coordination (2026-10-28): the user explicitly approved
> [GW-SR-AUTH-03 and its implementation contract](../auth/unified-registration-implementation-contract.md) with “Approve”.
> The separate employer/personal-phone/provisional-save flow and pre-upload/JSON dossier contracts below are historical within the replacement scope. Shared registration, requested account-email proof for new Users, atomic complete multipart submission/correction and documented 410 retirement now follow the approved replacement. Preserve existing Company review, private retained evidence, membership and delivery mechanisms; no automatic promotion or data deletion is authorized. This later scoped approval supersedes conflicting earlier wording below; historical approval evidence remains intact.

| Field | Value |
|---|---|
| Status | Product and Q10–Q17 implementation directions confirmed; concrete schema/API proposal ready for technical review |
| Date | 2026-10-18 |
| Last updated | 2026-10-27 |
| Product specification | [GW-SR-REC-03, version 0.2.1](company-onboarding-verification-enhancement-spec.md) |
| Current gap and work order | [Email-only implementation plan](company-onboarding-email-only-implementation-plan.md) |
| Historical delivery analysis | [2026-10-18 evidence snapshot](company-onboarding-verification-gap-analysis.md) |
| Current implementation inventory | [Backend onboarding](../../backend/employer-onboarding.md) |

## 1. Confirmation and scope

On 2026-10-18 the user confirmed the original concrete contract with “chốt”; [ADR 0005](../../architecture/decisions/0005-staged-employer-onboarding.md) records its pending-registration, scoped API/security, retained PostgreSQL evidence, Resend delivery and dependency-free UI choices. No dependency was added. On 2026-10-27 the user confirmed Q1–Q5 and Q7–Q9 of the email-only amendment and explicitly deferred Q6. [ADR 0007](../../architecture/decisions/0007-email-only-employer-onboarding.md) records the changed boundary.

The target now creates an official, restricted account at successful registration, requires unique email and declared phone across existing accounts, and uses actual email proof before Company preparation/submission or Recruiter invitation acceptance. SMS is removed as a target feature and prerequisite, including for already-enrolled accounts.

These are confirmed product requirements, not approval of an unspecified replacement schema/API. This documentation change implements no runtime behavior. Unaffected technical choices remain as previously authorized; concrete breaking API/schema amendments still need review under the root Agent Guide.

### 1.1 Follow-up confirmation and concrete proposal

The user confirmed Q10–Q17: backend/API/examples only; one canonical phone shared with Candidate; coordinated client contract changes; account retention on email-submission failure; old pending-proof/SMS route retirement with 410 and retained inactive records; blocking conversion on conflicting/invalid phones; Candidate edits to the shared number; and authenticated email confirmation without continuation tokens. [ADR 0008](../../architecture/decisions/0008-canonical-account-phone.md) records the canonical-phone trade-off.

The [current implementation plan](company-onboarding-email-only-implementation-plan.md) is the evidence-backed gap matrix and next work order. It proposes `users.phone_number`, normalized-email uniqueness, account/email proof snapshots, concrete registration/state/proof/410 responses, migration preflight, package dependencies and required tests. Those exact physical/API details remain a technical proposal for human review; the confirmed directions are not permission to guess a migration winner or change Q6.

Source inventory correction: this checkout contains no source frontend, although Maven declares a frontend resource location and historical delivery notes describe one. Do not treat generated target assets as source or promise a working shell from this backend-only plan.

## 2. Existing implementation versus target

| Boundary | Existing code / earlier contract | Confirmed target and required amendment |
|---|---|---|
| Registration | Separate pending attempts; official User created only after exact-attempt email proof and credential confirmation | Create official User at successful registration, email unverified, with distinguishable applicant state and no Company/membership. Design a compatible replacement for pending promotion. |
| Sign-in | New employer applicant cannot sign in until email promotion | Permit credential-based restricted sign-in before email proof; prevent every forbidden service effect. |
| Uniqueness | Official User email unique; pending claims may duplicate identifiers; employer phone unique only when verified; Candidate phones separate/nonunique | Enforce email and normalized declared phone uniqueness across existing accounts, including unverified accounts and Candidate contact data. Design data ownership, all-write-path enforcement and collision migration. |
| Proof | Account/attempt-bound email plus delivered SMS OTP | Email only; retain 24-hour email expiry, 60-second resend, replacement, single use, exact binding and applicable email abuse controls. Never fabricate phone verification. |
| Legal preparation | Provisional edits, uploads and submission/correction require email and phone | Require actual email proof, legal/document/declaration completeness and current scoped authority; no phone gate. |
| Recruiter activation | Invitation acceptance may precede personal proof; subsequent enhanced operations require email/phone | Require actual email proof before valid matching-email acceptance activates RECRUITER. Invitation determines Company; registration name grants nothing. |
| Owner assignment | Initial legal submission atomically creates Company/Owner/attempt | Preserve; Company starts PENDING, membership starts ACTIVE OWNER, recruitment waits for VERIFIED Company and existing permissions. |
| Existing enrolled accounts | Personal phone gate can block onboarding/operations; production SMS adapter fails closed | Remove phone gate for these accounts; keep actual email, Company, membership, User and permission checks and existing proof/history. |
| Unrelated legacy accounts | Scoped rollout; historical flags do not prove mailbox ownership | Preserve scope; this amendment does not newly enroll all legacy accounts or backfill genuine email proof. |

## 3. Persistence and migration boundaries

The current `V2026.10.18.73__employer_onboarding.sql` contains pending registrations, personal employer state, phone challenge fields, private legal documents, retained attempts and decision delivery. It remains immutable history.

Prepare a new migration/API proposal addressing:

1. Official unverified account creation with atomic identity reservation and applicant state. Preserve existing password hashing, opaque username/full-name mapping and Candidate login compatibility unless explicitly amended.
2. Pending attempts already issued before rollout are retained and inactive; old proof routes return 410 and applicants register afresh, as confirmed in Q14. Do not bulk-promote attempts, pick an account winner, merge data or discard private state.
3. One canonical account phone is confirmed in Q11; the concrete proposal places it on User. Audit all current Candidate/employer values and write paths. Same-User differing values, invalid phones and cross-User collisions block conversion; phone-less existing Users remain valid. Candidate updates target the shared source under Q16.
4. Account-bound email proof that no longer performs pending-to-User promotion. Proof alone must not issue a session, reveal another claimant's information or activate control of credentials supplied by someone else.
5. Existing phone proof/challenge history. Preserve old records; remove active SMS dependencies and OTP-only configuration through source changes and a reviewed compatibility plan, not by editing old migrations.
6. Current tokens/refresh sessions and persisted eligibility. Do not rely on token claims or UI controls alone, mass-revoke sessions or treat historical auto-verified flags as genuine mailbox evidence.

Identifier reservations survive email-proof expiry. Manual support is the selected route for disputed declared phone numbers; actors, evidence, audit and recovery outcomes remain undefined and must be reviewed before production. No automatic release, transfer or account takeover is authorized.

Q6 (already-ACTIVE Recruiter to new-Company Owner): **—; deferred**. This contract defines no new transition or mandatory departure path and authorizes no change to current one-active-membership constraints.

## 4. API/client amendment work

The table below identifies affected existing routes. The 410 retirement direction is confirmed; exact replacement registration/state/proof details are proposed in the current implementation plan and are not implemented.

| Current route | Required design / target behavior |
|---|---|
| `POST /api/employer-registrations` | Replace pending-attempt-only success with immediate official-account registration. Define response, identity conflicts, delivery failure behavior and old-client transition; retain the eight requested inputs and discard password confirmation. |
| `POST /api/employer-registrations/{id}/email/resend` and `/email/verify` | Retire with 410, retain inactive pending records and require fresh registration (Q14). New proof uses the authenticated account-bound routes. |
| `POST /api/auth/login`, refresh and `GET /api/auth/me` | Permit restricted employer sign-in before email proof; expose sufficient stage/capability information without granting Company role or unrestricted Candidate access. Preserve portal/session contracts. |
| `GET /api/me/employer-onboarding`, `/email/send`, `/email/verify` | Define restricted-account access and state responses without requiring phone enrollment or promoting another account. Preserve existing member proof binding. |
| `/api/me/employer-onboarding/phone/send` and `/phone/verify` | Return 410 under normal authentication without delivery/enrollment/write effects (Q14). No fake OTP/proof or automatic phone-verified flag. |
| `PUT /api/me/employer-onboarding/company`, document upload, initial submission and correction | Replace email-plus-phone gate with actual email proof plus relevant legal/authorization/version checks. Preserve retained evidence, immutable MST and atomic Company/Owner/attempt creation. |
| Existing recipient invitation acceptance | Add actual email-proof prerequisite before membership activation for the scoped onboarding journey; preserve recipient/secret, expiry/revocation, Company and membership safeguards. |
| Existing recruitment operations | Remove personal phone requirement while retaining actual email proof and all current Company/User/membership/operation gates. |

Preserve the existing response envelope, platform USER role, public/staff portal separation and unrelated Candidate APIs. Duplicate-identifier errors must not reveal another account's profile or Company. A concrete proposal must define response/error compatibility and migration timing before implementation.

## 5. Unaffected technical choices

- Keep feature-first MVC, Spring Data JPA and PostgreSQL; introduce no dependency or shared architecture through this amendment.
- Store legal document bytes privately in PostgreSQL with the previously confirmed 10 MiB limit, PDF/JPEG/PNG byte-signature checks, SHA-256 and authenticated downloads. Retain immutable submissions/declarations/decisions and existing database evidence guards.
- Keep PENDING/VERIFIED/REJECTED Company eligibility, with derived PENDING/CHECKED processing status and VERIFIED/REJECTED outcome. CHECKED alone grants no eligibility.
- Keep initial Company/Owner/attempt creation atomic; preserve Owner feedback/correction before Company approval, MST immutability, Staff principal attribution and optimistic concurrency.
- Keep scoped Resend/JDK HTTP verification/decision delivery, environment-backed credentials/origins, after-commit decision intent, bounded retry and stable provider idempotency. Provider acceptance is not mailbox arrival.
- Keep existing frontend packaging/session conventions pending any separate approved frontend change. The target UI needs no SMS screen and must show restricted sign-in and invitation/new-Company branches. No frontend framework or token-storage migration is selected here.

Production retention/disposal, Staff review/dispute procedures, real support contact, aggregate email abuse limits, configured sender/origins and job-discovery integration remain required. SMS vendor selection is no longer a target prerequisite.

## 6. Implementation and verification sequence

1. Review concrete registration/proof API and schema amendments, phone data ownership/collision inventory, outstanding-attempt migration and scoped rollout. Record actual approvals before implementing breaking changes.
2. Implement atomic immediate registration and system-wide identifier enforcement at all relevant write boundaries. Do not silently alter unrelated Candidate behavior beyond the agreed uniqueness rule.
3. Implement restricted sign-in and current-state backend gates together; prove old tokens/refresh and direct requests cannot bypass email proof.
4. Remove SMS prerequisites for legal preparation, submission/correction, invitation activation and enhanced employer operations. Preserve proof history and all unrelated moderation/authorization checks.
5. Publish the backend email-confirmation/client contract and HTTP examples; external frontend integration remains separate under Q10. Remove active OTP instructions/configuration references only when runtime changes are actually delivered.
6. Verify the scenarios in GW-SR-REC-03 section 9 at service, real HTTP/security and PostgreSQL boundaries. Cover normalized uniqueness races and collision rollout, unverified shell/denials, email expiry/resend/replay, invitation activation, atomic Owner creation, correction/history and enrolled-account SMS removal. Preserve ordinary Candidate/legacy controls.
7. Run documented backend tests and packaging for schema/config/build changes when implementation occurs; distinguish provider-backed and external-client checks from backend tests. This documentation-only revision does not claim those checks ran or the new journey works.

## 7. Historical implementation checkpoints

The original 2026-10-18 CHECKED mapping slice passed 171 tests. The subsequent email/SMS onboarding implementation passed 183 tests with zero failures/errors/skips and packaging/frontend syntax/DOM/link/whitespace checks, as previously recorded in [backend onboarding](../../backend/employer-onboarding.md). SMS production delivery remained unimplemented; tests used isolated delivery fakes. Those historical results cover the earlier implementation and do not validate the 2026-10-27 target.
