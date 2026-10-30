# GW-SR-REC-03 — Implementation Contract Proposal

> Unified registration coordination (2026-10-28): the user explicitly approved
> [GW-SR-AUTH-03 and its implementation contract](../auth/unified-registration-implementation-contract.md) with “Approve”.
> The separate employer/personal-phone/provisional-save flow and pre-upload/JSON dossier contracts below are historical within the replacement scope. Shared registration, requested account-email proof for new Users, atomic complete multipart submission/correction and documented 410 retirement now follow the approved replacement. Preserve existing Company review, private retained evidence, membership and delivery mechanisms; no automatic promotion or data deletion is authorized. This later scoped approval supersedes conflicting earlier wording below; historical approval evidence remains intact.

| Field | Value |
|---|---|
| Status | Human confirmation recorded; implementation in progress; SMS/production readiness outstanding |
| Date | 2026-10-18 |
| Last updated | 2026-10-24 |
| Product specification | [Company onboarding enhancement](company-onboarding-verification-enhancement-spec.md) |
| Delivery analysis | [Current flow and gap analysis](company-onboarding-verification-gap-analysis.md) |
| Working branch | `feat/GW-73-enhance-company-verification-flow` |
| Starting revision | `717b1905d9d035406f37114f78a990a1c0517964` |

## 1. Approval boundary

The product journey and the provisional-registration/derived-CHECKED directions were approved by the user. The referenced documents explicitly leave physical schema, API design, frontend, delivery integrations, rollout and retention unresolved. Root `AGENTS.md` requires explicit approval before selecting those architectures, changing schema or introducing breaking API changes.

The user subsequently confirmed this concrete contract with “chốt” on 2026-10-18. That confirmation authorizes its schema/API, scoped authorization, PostgreSQL document storage, Resend delivery and dependency-free frontend choices. [ADR 0005](../../architecture/decisions/0005-staged-employer-onboarding.md) records those choices. No dependency addition or upgrade is proposed. SMS vendor selection, production retention/disposal, aggregate abuse limits and legacy rollout remain open inputs rather than implicitly selected policies.

## 2. Proposed data changes

Add new Flyway migrations; do not edit existing migrations or reset existing databases.

| Data | Proposed persistence and constraints |
|---|---|
| Pending employer registration | Separate table with UUID attempt ID, hashed continuation secret, normalized email, password hash, full name, gender, proposed phone, Company name/address, proof state, timestamps and optimistic version. Multiple pending attempts may claim one email; they are not official Users. Password confirmation is checked and discarded. |
| Email verification challenge | Random single-use secret stored as a hash, purpose and exact account/registration binding, exact normalized email, expiry, consumed state and resend timestamp. A new issuance replaces prior proof. No secret in query-string logging or application logs. |
| Personal employer-verification record | User-scoped record with actual mailbox-proof timestamp, normalized proposed phone, phone-verification timestamp and applicant origin. A unique partial index on verified normalized phone prevents simultaneous ownership. Official User email remains globally unique. |
| Phone challenge | Account/phone-bound code digest protected with a server-side secret, expiry, failed-attempt count, resend timestamp and version/row lock. Store neither plaintext OTP nor a digest vulnerable to offline enumeration without a secret. |
| Provisional Company information | User-scoped editable data after successful email promotion; retain before complete legal submission without creating a Company or membership. |
| Private legal document | New table for immutable document bytes, SHA-256, original filename, allowed media type, size, uploader and upload timestamp. Proposed initial limit: 10 MiB; PDF, JPEG or PNG. Private download endpoint checks current Company Owner or Platform Staff authority. No public document URL. |
| Legal submission attempt | Company ID, submitter ID, immutable submitted Company/address/MST/position snapshot, legal/authorized-representative selection, declaration version/text, acceptance time, document ID, review decision/reason/reviewer/time and attempt version. One pending attempt per Company. Earlier attempts remain readable by authorized parties. |
| Decision delivery intent | Scoped table for committed decision ID, recipient User/email snapshot, delivery state, attempts and next-attempt time. A unique decision/event identity makes retries idempotent. Pending delivery never changes a committed verification decision. |

The private-document proposal stores bytes in PostgreSQL to avoid selecting a new external storage service. It requires size limits, authenticated streaming and deployment capacity review; it is a proposal, not a claim that existing certificate URLs are private or immutable. Existing URL-only dossiers remain historical data and must not be presented as newly retained document bytes.

Retention timing, deletion, abandoned-registration cleanup and document disposal require a separate reviewed policy before production release. This proposal does not choose indefinite retention or silently schedule deletion.

## 3. Proposed additive API contracts

Preserve the existing Candidate registration/login flow and response envelope. Add a scoped employer registration journey; keep the platform role USER. No global RECRUITER role or user-entered username is introduced.

| Operation | Proposed endpoint | Principal / important rule |
|---|---|---|
| Begin employer registration | `POST /api/employer-registrations` | Public, throttled; returns only the new attempt's opaque continuation material, never another claimant's data. |
| Resend email proof | `POST /api/employer-registrations/{id}/email/resend` | Requires that attempt's continuation proof; 60-second cooldown. |
| Confirm email and promote | `POST /api/employer-registrations/{id}/email/verify` | Exact-attempt email secret plus continuation/password confirmation. Atomically create at most one official User and employer record. Do not issue an authenticated session automatically. |
| Read own onboarding stage | `GET /api/me/employer-onboarding` | Authenticated; exposes verified flags/stage and permitted next action, not verification secrets. |
| Send/verify phone OTP | `POST /api/me/employer-onboarding/phone/send`, `POST /api/me/employer-onboarding/phone/verify` | Authenticated with actual email proof; new SMS is invalid unless delivery is accepted; 5-minute/5-attempt/60-second rules apply. |
| Update provisional Company | `PUT /api/me/employer-onboarding/company` | Authenticated and phone verified; optimistic version required. |
| Upload/view private legal document | `POST /api/me/employer-onboarding/documents`, `GET /api/company-verification-documents/{id}` | Upload after personal verification; private reads require ownership/current Company authority or Platform Staff. Download of an upload not yet submitted remains uploader-scoped. |
| Submit and correct dossier | Existing Company submission/correction paths, with reviewed extended request DTOs | New-onboarding accounts require actual personal proof, complete fields, retained document and declaration. Initial Company/Owner/attempt creation is atomic; corrections retain Company/MST. |
| Read Owner/Staff verification | Existing verification responses | Add `processingStatus` and `reviewOutcome`; retain `verificationStatus` and existing fields for compatibility. Proposed attempt/history fields require contract review. |

Full name is retained verbatim in the employer record. A server-generated opaque username preserves the existing unique-username requirement, and the full name is placed in the existing display-name representation without attempting to infer Vietnamese family/given names. Existing Candidate name fields and login identifiers remain compatible; the exact mapping is part of approval.

Email promotion must not activate an attacker's known password just because the mailbox owner opens an unsolicited link. Explicit completion must establish control of the correct attempt and confirm credentials; an existing official account always wins uniqueness, and no account/data merge occurs. Cross-device completion and private recovery must be documented and tested with this constraint.

Request-body secrets must be redacted. Public errors must not disclose another registration's details. Verification GET links display a confirmation page; consuming proof uses POST. API errors use existing envelopes and reviewed scoped codes rather than a new platform error architecture.

## 4. Authorization and existing-data proposal

- Add a feature-scoped onboarding eligibility check before protected service effects. Read current persisted personal/Company state; token claims alone do not grant stage eligibility.
- New employer applicants can only search/view jobs and perform their eligible verification/settings actions. Candidate profile/CV access is denied before any skeleton-profile creation or upload.
- Keep Owner status, rejection feedback and correction reachable while Company approval is pending/rejected. Do not put a VERIFIED-only check in a shared helper that also protects these recovery actions.
- An invited Recruiter can retain existing invitation acceptance behavior, but employer operations require actual personal email and phone proof. Invitation/delegation roles remain unchanged.
- Preserve current Candidate accounts, Company identity/MST, audit records, historical contact emails and Platform Staff capability. Do not reset accounts or revoke all tokens.
- Existing `email_verified=true` values remain historical compatibility flags, not proof for the new personal-verification record. Existing members need a personal-proof completion path before any newly gated employer operation. Whether rollout enables these gates for existing members immediately or only after a migration window remains a required explicit rollout decision.
- Existing job-seeker-to-new-Company conversion and post-approval Candidate capability expansion stay deferred, as agreed in the product specification.

## 5. Delivery integrations and frontend proposal

| Area | Proposed approach | Unresolved input |
|---|---|---|
| Verification/decision email | Feature-scoped mailer using existing Resend HTTPS integration style and JDK HttpClient; environment-backed sender/credential and allowed verification origin. Keep invitation behavior unchanged. | Deployment sender/credentials and approved confirmation URL. |
| SMS OTP | Feature-scoped sender with provider HTTP adapter, test fake and fail-closed behavior when unconfigured. Never log/return an OTP as a replacement for real delivery. | User must select the actual SMS provider; provider-specific adapter/credentials cannot be claimed complete before this. |
| Decision retries | Narrow persisted delivery intent, recorded in the same decision transaction; worker sends after commit with bounded backoff and operator-visible failure. Do not expand membership-only event types silently. | Approve this scoped notification approach and retry/abuse limits. |
| Legal documents | Private, immutable PostgreSQL-backed bytes as proposed in section 2. | Approve size/types, retention/access policy and deployment capacity. |
| Frontend | Dependency-free HTML/CSS/JavaScript under `frontend/`, packaged as Spring static resources using existing Maven resources support; same-origin APIs. In-memory access token, explicit sign-in after email verification; no external frontend framework. | Explicit frontend selection approval; final retention/privacy wording and actual support contact. |

Frontend scope covers employer registration, verification confirmation, sign-in, phone proof, Company settings, private upload/declaration, status/rejection/correction and the Staff review view. Existing job discovery is not implemented in the repository; integrating search/view remains a separate dependency rather than a fabricated working feature.

## 6. Implementation and verification sequence

1. Review this contract; record accepted consequential choices in an ADR and synchronize the overlapping AUTH/REC requirement notes.
2. Add migrations/models and test atomic pending-registration promotion, normalized ownership races and private claim recovery using isolated PostgreSQL.
3. Add personal verification and gates with tests first at service and real HTTP/security seams. Verify ordinary Candidate controls and existing-session behavior.
4. Add retained legal documents/attempts and extend initial Company/Owner and correction transactions. Preserve MST uniqueness, optimistic locking and audit rollback.
5. Extend Staff review and derived CHECKED responses; bind reviewer identity to the authenticated principal under a reviewed header-compatibility change.
6. Implement and test configured delivery adapters and after-commit decision retry. Test delivery failure without decision rollback.
7. Build the approved frontend, run staged journey checks, and document deployment/configuration and unresolved production-readiness items.
8. Run Java compilation and focused tests during development, then the full suite and required packaging check at completion. Perform Standards/Spec review and commit the completed authorized work on the current branch per the invoked implement skill.

The local `tdd` skill was not found. The implement skill says to use it where possible; meaningful tests can still be written before behavior changes without installing another skill or dependency.

## 7. Work already safe under confirmed scope

The derived CHECKED mapping is an additive response change and needs no database migration. It can be delivered independently with a JSON contract test for Staff and Owner responses across all three stored states. Other parts of this proposal remain subject to their explicit approvals and required provider input.

### Implementation checkpoint — 2026-10-18

- Implemented derived `processingStatus` and `reviewOutcome` on Staff and Owner verification responses; existing `verificationStatus` remains intact.
- Added a three-case JSON contract test covering both response types. It failed for the missing fields before implementation and passed afterward.
- Java compilation passed. The full backend suite passed: 171 tests, zero failures/errors/skips, using the documented isolated PostgreSQL test service. Mockito required execution outside the sandbox to attach its Java agent; the initial sandbox baseline failed for that environmental reason.
- Standards and Spec reviews found no actionable defect in the mapping slice. The Spec review explicitly confirmed that email/SMS verification, retained legal submissions and decision delivery remain unimplemented.
- At that initial checkpoint packaging was not repeated: the slice changed no dependency, configuration or build metadata, and introduced no migration. This describes the initial slice, not the subsequent implementation.

### Subsequent authorized implementation

The confirmed contract is now being implemented beyond the initial mapping slice. See [backend onboarding documentation](../../backend/employer-onboarding.md) for actual APIs, compatibility, delivery configuration, migration, test boundaries and remaining production prerequisites. Real SMS delivery is not yet implemented because the provider remains unselected; tests substitute an isolated sender and production fails closed.

Implementation verification: 183 backend tests passed with no failures/errors/skips; packaging, frontend syntax/DOM references, documentation links and whitespace checks passed. Final two-axis review reported no remaining actionable findings. No dependency was added. SMS integration and production-readiness inputs remain outstanding, so this checkpoint does not declare the complete production journey delivered.
