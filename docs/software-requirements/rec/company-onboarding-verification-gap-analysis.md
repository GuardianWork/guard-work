# Company Onboarding Verification — Current Flow, Gaps, and Change Order

| Field | Value |
|---|---|
| **Analysis ID** | GW-ANALYSIS-REC-03 |
| **Target specification** | [GW-SR-REC-03](company-onboarding-verification-enhancement-spec.md) |
| **Date / Last Updated** | 2026-10-18 / 2026-10-27 |
| **Owner** | VyTrg (proposed, following Company Verification ownership) |
| **Human reviewer** | VyTrg (proposed) |
| **Document status** | Draft implementation analysis |
| **Evidence boundary** | Repository source, migrations, and test definitions; no running deployment or database inspected |

> Current evidence-backed gap matrix, schema/API proposal and ordered work packages: [2026-10-27 email-only implementation plan](company-onboarding-email-only-implementation-plan.md).

> Historical analysis: its 2026-10-18 evidence and implementation directions below are retained for traceability, not current target requirements. On 2026-10-27, user-confirmed Q1–Q5/Q7–Q9 superseded pending-to-official promotion, SMS prerequisites, verified-only phone uniqueness and invitation acceptance before email proof. Q6 remains deferred. Use [GW-SR-REC-03 v0.2.1](company-onboarding-verification-enhancement-spec.md) and the [amended implementation contract](company-onboarding-implementation-contract.md) for the current target and work order. Do not implement the historical SMS/provider tasks below.

## 1. Purpose and confirmed decisions

Terminology update (2026-10-24): this analysis retains its 2026-10-18 evidence
snapshot and historical gaps; it is not a current implementation inventory.
Platform actors and source links use current Staff naming. Current onboarding
implementation status is recorded in [backend onboarding](../../backend/employer-onboarding.md).
GW-81 resolved G18 actor attribution: the current Staff controller requires a
verified principal and does not trust client-supplied actor/role headers.

This document compares the target applicant journey with implemented behavior and identifies changes that must precede delivery. It is not an implementation or a replacement for the target specification.

The user explicitly stated that they had reviewed and approved the target specification. Its file metadata still says Draft and its introductory note predates that statement. Record the human approval in the review process and reconcile that stale note; do not interpret it as cancellation of the user's current approval. This analysis does not independently change a specification to Approved.

During the follow-up design interview, the user accepted these preferred implementation directions:

1. **Keep unverified registration provisional.** Preserve one email per official User; keep unverified registration information separately and create the official account only after proof of mailbox ownership. Pending claims cannot permanently reserve another person's email or expose/transfer another claimant's information.
2. **Derive CHECKED.** Retain Company eligibility states `PENDING`, `VERIFIED`, and `REJECTED`. Derive processing status and review outcome for presentation/contracts rather than requiring a stored `CHECKED` Company state.

These settle the two main directions needed for this comparison. Exact schema, APIs, delivery integrations, and rollout remain implementation-readiness work; approval of this analysis must not be read as selecting them.

## 2. Actual implemented journey

```text
POST /api/auth/register
    firstName + lastName + username + email + password
    -> reject any existing username/email
    -> create USER / ACTIVE / emailVerified=true

POST /api/auth/login
    -> verify password and ACTIVE status
    -> issue access + refresh tokens

Authenticated User without active Company Membership
    -> POST /api/companies/verification-request
       Company name + MST + Company email + certificate URL
    -> create Company PENDING + first Owner atomically

Platform Staff
    -> review Company queue
    -> VERIFIED or REJECTED, expectedVersion required
    -> decision audit recorded; no decision email

Rejected Company's Owner
    -> GET /api/companies/me/verification
    -> PUT /api/companies/me/verification with corrections and expectedVersion
    -> same Company/MST, mutable fields overwritten, status PENDING
```

There is no email-link/phone-OTP stage, provisional Company editing UI, or staged account capability policy in this journey. The checked-in `frontend/` contains only `.gitkeep`. Job discovery is also not implemented in the inspected backend; the target's search/view access is an integration dependency, not evidence of an existing working screen.

Core evidence: [registration and login](../../../backend/src/main/java/com/guardwork/backend/auth/AuthService.java#L56), [Company creation](../../../backend/src/main/java/com/guardwork/backend/company/service/CompanyService.java#L49), [Owner verification APIs](../../../backend/src/main/java/com/guardwork/backend/company/controller/CompanyController.java#L45), [Staff review](../../../backend/src/main/java/com/guardwork/backend/staff/service/StaffCompanyVerificationService.java).

## 3. Evidence-backed gap matrix

Target requirement IDs in this table refer to GW-SR-REC-03. “Partial” means existing behavior should be extended while preserving its safeguards.

| Gap | Target references | Current behavior and evidence | Required adjustment |
|---|---|---|---|
| G01 — Registration fields | REQ-REC-01 | [RegisterUserRequest](../../../backend/src/main/java/com/guardwork/backend/auth/RegisterUserRequest.java#L3) accepts first/last name, username, email, password only. | Add the scoped employer-applicant registration contract: full name, phone, provisional Company name/address, gender and confirmation. Decide full-name/username compatibility without changing ordinary Candidate registration implicitly. |
| G02 — Pending registration and email ownership | REQ-REC-01–03; BR-REC-01–03 | [Registration](../../../backend/src/main/java/com/guardwork/backend/auth/AuthService.java#L60) creates a User immediately and rejects every existing email. [V1](../../../backend/src/main/resources/db/migration/V1__create_users.sql#L5) and [User](../../../backend/src/main/java/com/guardwork/backend/user/model/User.java#L35) require globally unique official email/username. | Introduce separately persisted provisional registration, account-bound proof and atomic promotion after verified email. Preserve official email uniqueness and existing lookup assumptions. Define safe continuation when a pending claim already exists. |
| G03 — Legacy email flags | REQ-REC-02–03 | Email defaults to true in [User](../../../backend/src/main/java/com/guardwork/backend/user/model/User.java#L50), [V3](../../../backend/src/main/resources/db/migration/V3__auth_and_access_control.sql#L4), and [registration](../../../backend/src/main/java/com/guardwork/backend/auth/AuthService.java#L75). | Remove automatic proof for the new journey. Since promotion follows actual email proof, the new official User may legitimately start email-verified. Do not blindly set every new User false or treat legacy true as mailbox evidence. Resolve current-account migration separately. |
| G04 — Verification challenges | REQ-REC-02–05; BR-REC-04–07 | [AuthController](../../../backend/src/main/java/com/guardwork/backend/auth/AuthController.java#L29) has no email-verification or SMS-OTP operations. | Add email proof and phone proof: 24-hour link, 5-minute OTP, 5 incorrect attempts, 60-second resend delay, replacement, expiry and single-use semantics. Existing password-reset tokens are a different purpose. |
| G05 — Verified phone identity | REQ-REC-02, 05; BR-REC-01–03 | [User](../../../backend/src/main/java/com/guardwork/backend/user/model/User.java#L29) has no verified account phone. Candidate profile phone is editable via [CandidateProfileService](../../../backend/src/main/java/com/guardwork/backend/candidate/CandidateProfileService.java#L67). | Add normalized Vietnamese account phone and ownership/verification state; guarantee one verified owner under races. A Candidate contact number is not proof. Define any synchronization separately. |
| G06 — Restricted applicant identity and session data | REQ-REC-04, 13 | [User](../../../backend/src/main/java/com/guardwork/backend/user/model/User.java#L44) has generic USER/status only. [CurrentUserResponse](../../../backend/src/main/java/com/guardwork/backend/auth/CurrentUserResponse.java#L3) exposes no onboarding stage. Membership does not exist before dossier submission. | Persist enough applicant-origin/stage information to distinguish this journey from ordinary Candidates and drive client capabilities. Keep Company Role separate from platform role. |
| G07 — Backend capability enforcement | REQ-REC-04, 07, 13–14 | [SecurityConfig](../../../backend/src/main/java/com/guardwork/backend/auth/security/SecurityConfig.java#L41) requires authentication broadly; [CompanyAccessService](../../../backend/src/main/java/com/guardwork/backend/membership/service/CompanyAccessService.java#L32) checks ACTIVE but not personal verification. | Enforce staged access before service effects: search/view plus permitted verification/settings only; personal verification before dossier submission; personal verification and Company approval before employer operations. Preserve Owner status/correction access before approval. |
| G08 — Existing sessions and refresh | REQ-REC-03–04, 13 | [Login](../../../backend/src/main/java/com/guardwork/backend/auth/AuthService.java#L99) and [refresh](../../../backend/src/main/java/com/guardwork/backend/auth/AuthService.java#L154) check ACTIVE. [JWT filter](../../../backend/src/main/java/com/guardwork/backend/auth/security/JwtAuthenticationFilter.java#L39) creates a principal from token claims. | Do not rely only on a new login check or UI. Define current-session compatibility and enforce eligibility at protected operations, so an old access/refresh token cannot bypass applicable gates. |
| G09 — Candidate side effects | REQ-REC-04 | [CandidateProfileService.getOrCreateProfile](../../../backend/src/main/java/com/guardwork/backend/candidate/CandidateProfileService.java#L34) may create a skeleton even on profile retrieval; [CV controller](../../../backend/src/main/java/com/guardwork/backend/candidate/cvs/CandidateCvController.java#L23) has no staged applicant rule. | Deny restricted applicants before profile creation or CV operations; preserve ordinary Candidate functionality. Applications are a downstream boundary to cover when implemented. |
| G10 — Provisional Company and dossier information | REQ-REC-06–08 | [Company DTO](../../../backend/src/main/java/com/guardwork/backend/company/dto/CompanyRegistrationRequest.java#L6) and [Company model](../../../backend/src/main/java/com/guardwork/backend/company/model/Company.java#L25) omit work address, submitter position, representative selection, and declaration. | Transfer the verified applicant's provisional information into editable post-phone settings; require complete legal submission and explicit commitment. Keep incomplete onboarding outside the Staff queue. |
| G11 — Separate Company email | REQ-REC-06–08; target section 3.1 | [CompanyService](../../../backend/src/main/java/com/guardwork/backend/company/service/CompanyService.java#L85) requires Company email and rejects duplicates; correction can replace it. [V4](../../../backend/src/main/resources/db/migration/V4__add_company_email.sql#L1) adds a nullable column with a nonunique index. | Remove the new journey's separate-email input/requirement and use the submitting account's verified email for decisions. Decide historical data and DTO compatibility. This is a service/contract requirement today, not a database NOT NULL/unique constraint. |
| G12 — Processing state | REQ-REC-10, 13 | [VerificationStatus](../../../backend/src/main/java/com/guardwork/backend/company/model/VerificationStatus.java) and [V2 constraint](../../../backend/src/main/resources/db/migration/V2__admin_governance_and_audit.sql#L6) support only PENDING/VERIFIED/REJECTED. | Follow the confirmed derived mapping in section 4. Add reviewed response/filter mapping without replacing VERIFIED checks throughout membership logic unnecessarily. |
| G13 — Dossier/document history | REQ-REC-12, 16 | [Staff service](../../../backend/src/main/java/com/guardwork/backend/staff/service/StaffCompanyVerificationService.java) records old/new decision payloads. [Correction](../../../backend/src/main/java/com/guardwork/backend/company/service/CompanyService.java#L154) overwrites current information and clears the reason; Company stores a certificate URL. | Preserve each submitted attempt, declaration, reviewed document version and decision. A mutable URL plus decision audit does not guarantee retained document content or submission history. Design this before extending resubmission. |
| G14 — Decision email | REQ-REC-11; BR-REC-12 | [Staff review](../../../backend/src/main/java/com/guardwork/backend/staff/service/StaffCompanyVerificationService.java) records audit and returns without mail. [NotificationIntentService](../../../backend/src/main/java/com/guardwork/backend/notification/service/NotificationIntentService.java#L21) accepts only membership event types. | Add a reviewed after-commit decision-delivery contract with verified recipient, retry and failure visibility. Invitation email and membership inbox are existing scoped integrations, not automatic approval for a general notification redesign. |
| G15 — Invited Recruiter eligibility | REQ-REC-14 | [RecipientInvitationService](../../../backend/src/main/java/com/guardwork/backend/invitation/service/RecipientInvitationService.java#L164) matches active user email to the invitation and checks Company eligibility; it does not check personal email/phone proof. | Require personal verification before employer operations. The target does not require blocking invitation acceptance itself. Leave role/delegation redesign outside scope. |
| G16 — Duplicate MST and guidance | REQ-REC-15 | [CompanyService](../../../backend/src/main/java/com/guardwork/backend/company/service/CompanyService.java#L102) and V2 tax-code uniqueness already block duplicates, but email and MST conflicts share one error and there is no support guidance. | Preserve unique MST and private ownership boundaries; distinguish the remaining MST conflict after removing Company-email checks. Add Vietnamese support guidance without exposing the existing dossier/Owner. |
| G17 — Frontend and discovery integration | REQ-REC-01–15 | `frontend/` has only `.gitkeep`; no job discovery controller was found in backend source. | Build registration/settings/status/locked-state screens after approved frontend selection. Treat search/view jobs as a delivery dependency rather than claiming it already works. |
| G18 — Staff actor integrity | REQ-REC-09, 16 | Historical finding, resolved by GW-81: the former controller accepted client actor/role overrides. The [current Staff controller](../../../backend/src/main/java/com/guardwork/backend/staff/controller/StaffCompanyVerificationController.java) derives identity from its authenticated principal; `/api/staff/**` requires Platform `STAFF`. | Preserve verified-principal attribution and reject header-only access. This historical finding did not imply anonymous access; do not reintroduce client-supplied actor authority. |

## 4. Confirmed CHECKED mapping

| Persisted Company verification state | Derived processing status | Derived review outcome | Company approved for employer eligibility? |
|---|---|---|---|
| PENDING | PENDING | None | No |
| VERIFIED | CHECKED | VERIFIED | Yes, subject to all other gates |
| REJECTED | CHECKED | REJECTED | No |

This direction satisfies the target's conceptual status/outcome model. `CHECKED` must never become a substitute for `VERIFIED` in authorization. Response field names, backward-compatible additions, Staff filters and per-attempt history need a reviewed contract. Historical attempts retain their own outcomes after current Company state returns to PENDING.

## 5. Existing behavior to preserve

- **Atomic initial ownership:** [CompanyService](../../../backend/src/main/java/com/guardwork/backend/company/service/CompanyService.java#L48) already creates Company and first Owner in one transaction. [V5](../../../backend/src/main/resources/db/migration/V5__company_ownership.sql#L17) enforces membership/ownership constraints. Extend this boundary rather than moving Owner creation to initial registration.
- **Company isolation:** one active membership per User, Owner-only private dossier access, suspension checks, and concealed unrelated identifiers remain required.
- **Moderation safeguards:** Staff-only access, pending-only decisions, rejection reason length 10–1000, optimistic concurrency, and immutable decision audit already exist.
- **Correction identity:** correction already keeps Company ID and MST and requires REJECTED plus the expected version. Retain this while adding full dossier-attempt history.
- **Invitations:** existing secret/email binding, Company verification checks, membership activation and revocation remain in scope for regression protection, not a role-system redesign.

## 6. What must change first

The order below is a dependency plan. Tasks with unresolved technical contracts begin with design/review, not immediate coding.

| Order | Work package | Prerequisite / why this comes first | Completion evidence |
|---|---|---|---|
| 0 | Reconcile approved requirements and define affected-account scope | Record current human approval; align AUTH/REC texts with the new journey and the two confirmed directions. Keep existing-account conversion deferred. | Traceability to GW-SR-REC-03; explicit compatibility and rollout decisions; no hidden conflict with ordinary Candidate access. |
| 1 | Design provisional registration, identity and onboarding state | G01–G06 must be resolved before proof endpoints or gates. Preserve unique official email; decide full-name/username mapping, account promotion, verified phone, private pending-claim recovery and onboarding status response. | Reviewed schema/API proposal, new migration plan, atomic promotion/verified-ownership scenarios and a distinguishable applicant stage before Company creation. |
| 2 | Implement personal verification and backend stage gates together | G04–G09 depend on identity/provisional data. Turning on new form fields alone leaves current direct API access open. | Working email proof, SMS proof and session rules; correct resend/expiry/race behavior; restricted profile/CV/Company requests denied, ordinary Candidate controls still pass. |
| 3 | Design retained legal submissions and document access | G10/G13 must be resolved before more mutable dossier fields or resubmissions are added. A URL is not a complete upload/retention design. | Reviewed document storage/validation/access and retention contract; immutable attempt/declaration/document association; no incomplete Company in review queue. |
| 4 | Extend complete submission, Company-email contract and MST handling | Depends on verified personal identity, stage gates and retained submission model. Reuse existing Company/Owner transaction. | Phone-verified applicant submits complete dossier; account email used; declaration retained; duplicate MST blocked; concurrent failure leaves no orphan Company/Owner/attempt. |
| 5 | Adapt Staff review, derived CHECKED and correction history | Depends on the attempt model; preserve current moderation safeguards. Review G18 actor integrity before relying on new decision history. | Review and correction refer to exact retained attempt; correct mapping and feedback; one decision per version; same Company/MST on resubmission. |
| 6 | Add decision email after commit | Depends on stable decision/attempt identity, account-email recipient rules, and an approved delivery/retry design. | Approved/rejected email; failure visible/retryable; duplicate delivery controlled; committed decision survives mail failure. |
| 7 | Integrate UI, invited-member gates and discovery dependency | Screens consume verified backend state; frontend architecture is still unselected. Recruiter internal authorization remains unchanged. UI design can run earlier after contracts stabilize. | Staged settings and locked-state journey; invited Recruiter personal proof without another dossier; real support contact; end-to-end acceptance checks including search/view integration. |

**Start with packages 0–1.** Replacing the Company enum, adding UI-only locks, or adding a phone column without ownership/challenge rules does not establish the planned flow. Packages 3 and UI design can progress alongside personal-verification implementation once their contracts are independently reviewed.

## 7. Decisions still needed before the relevant implementation package

These are explicit backlog items, not silently selected architecture. The present task is the gap analysis; it does not resolve every implementation choice.

| Decision | Needed before | Constraint already settled |
|---|---|---|
| Employer-specific registration entry/contract, full-name mapping and username generation/compatibility | Package 1 | Preserve ordinary Candidate behavior and platform USER; provisional employer registration precedes official account creation. |
| Pending-attempt identifier, private continuation/reclaim, promotion idempotency and stale-claim cleanup | Packages 1–2 | A pending claim cannot reserve an identifier indefinitely or transfer another claimant's private data; promote only after exact-account/email proof. |
| Existing users with auto-verified flags and already-issued sessions | Any rollout touching those users | Legacy true is not independent mailbox evidence. Do not mass-reset accounts, trust all flags as fresh proof, or revoke sessions without an approved migration. Existing-user-to-employer conversion remains deferred. |
| Candidate contact phone versus verified account phone | Package 1 if linked | The editable Candidate phone must never automatically set account phone verified. Synchronization is not required by the target. |
| New schema, endpoint/status response compatibility and errors | Packages 1, 4–5 | No changes to committed Flyway history; derive CHECKED while preserving current Company eligibility states. |
| Legal upload/storage, immutable document versions, access, retention/deletion, Staff checklist | Packages 3–5 | Keep every submission and declaration; authorization-letter upload is not mandatory; retention is not implicitly indefinite. |
| SMS/email providers, credentials, aggregate abuse limits, delivery/retry/recipient binding | Packages 2 and 6 | Confirmed per-attempt limits still apply; no provider, new dependency or shared notification architecture selected. |
| Frontend approach and real support contact | Package 7 / release | Frontend is not built; support placeholder is temporary and must not invent a contact destination. |
| Verified email/phone changes and post-approval Candidate capabilities | Before delivering those paths | These remain outside the defined new-applicant journey; Company approval alone does not grant unrelated Candidate-data privileges. |

## 8. Verification plan

The current analysis reads tests as evidence; it does not claim they passed during this task.

| Boundary | Existing checks to preserve / amend | New proof required |
|---|---|---|
| Authentication unit behavior | [AuthServiceTest](../../../backend/src/test/java/com/guardwork/backend/auth/service/AuthServiceTest.java): current registration, login, refresh assumptions | Provisional-to-official promotion, actual proof, normalized ownership, expiry/resend/attempt limits, replay and simultaneous verification. Amend expectations for the scoped new journey; retain Candidate controls. |
| Actual HTTP security | [AuthControllerTest](../../../backend/src/test/java/com/guardwork/backend/auth/controller/AuthControllerTest.java) uses mocked standalone MVC and cannot establish the real SecurityFilterChain | Real security-boundary requests for restricted Candidate profile/CV, Company submission, current-stage response and employer access; old-token/refresh bypass cases. |
| Company and Owner transactions | [CompanyOwnershipHttpTest](../../../backend/src/test/java/com/guardwork/backend/membership/CompanyOwnershipHttpTest.java) covers concurrency and audit-failure rollback | Preserve atomicity with retained attempts/declarations and account-email-only input; prove MST races create no partial records. |
| Correction and history | [CompanyCorrectionHttpTest](../../../backend/src/test/java/com/guardwork/backend/membership/CompanyCorrectionHttpTest.java) covers private reads, concurrent correction and retained moderation identity | Old attempt, document content/version and declaration survive correction; stale correction cannot replace reviewed evidence; only same rejected Company can resubmit. |
| Staff decision and actor | [StaffCompanyVerificationServiceTest](../../../backend/src/test/java/com/guardwork/backend/staff/service/StaffCompanyVerificationServiceTest.java), [controller test](../../../backend/src/test/java/com/guardwork/backend/staff/controller/StaffCompanyVerificationControllerTest.java) | Derived status/outcome, exact attempt decision, authenticated actor attribution, concurrent decision safety and mandatory reason. |
| Invitation and scoped notifications | [RecipientInvitationHttpTest](../../../backend/src/test/java/com/guardwork/backend/invitation/RecipientInvitationHttpTest.java), [membership notification HTTP test](../../../backend/src/test/java/com/guardwork/backend/invitation/MembershipNotificationWorkerHttpTest.java) | Existing invitation flow preserved; personally unverified member cannot perform employer operations; new decision email does not assume membership inbox supports it. |
| Delivery and complete journey | No decision-email/onboarding UI implementation exists | Commit survives mail failure, bounded retries and recipient binding; configured delivery, restricted screens, correction, duplicate-MST guidance and approved employer eligibility. |

Use PostgreSQL-backed checks for ownership/promotion/Company races and the existing [backend test workflow](../../backend/testing.md). Once code changes begin, run the required backend checks; packaging is additionally required for dependency/configuration/build changes. UI verification follows the approved frontend implementation.

## 9. Handoff and limits

- Ready now: evidence-backed gaps, reusable safeguards, the two confirmed directions and a dependency-ordered delivery plan.
- Next concrete work: prepare package 0–1 contract/migration proposals and implementation tickets; obtain the applicable technical approvals before changing APIs, schema, dependencies or architecture.
- This task changes documentation only. No application code, migrations, approved-spec status, deployment or Git history is changed.
- No application tests or live deployment checks were run because this is a static comparison. Documentation checks cover paths, unique gap IDs and formatting.
