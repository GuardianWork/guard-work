# Unified Registration and Recruiting Access — Implementation Contract

| Field | Value |
|---|---|
| Contract ID | GW-SR-AUTH-03-CONTRACT |
| Parent specification | [GW-SR-AUTH-03](unified-registration-and-recruiting-access-spec.md) |
| Status | Approved — explicit user approval recorded on 2026-10-28 |
| Last updated | 2026-10-28 |
| Human owner / approver | User (name requested; pending response) |
| Scope | Local tracker ticket 01; approved contract for implementation tickets 02–06 |

## 1. Approval evidence and requirement coordination

On 2026-10-28, after the concrete document and approval choices were presented, the user explicitly replied “Approve”. This approves the complete contract, including the additive migration, breaking retirement and Company submission contracts, email lifecycle, scoped access rules and verification seams. Approval is attributed to the User; their display name has been requested and remains pending, rather than inferred from historical feature owners. The following scoped supersession preserves historical decision evidence:

| Direction | Documents explicitly superseded within this scope |
|---|---|
| UREG-001, 002, 012 | AUTH-01 registration's automatic verification; REC-03/ADR 0005 separate employer identity and Candidate restrictions |
| UREG-003, 008, 009, 011, 013 | REC-03/ADR 0005 phone prerequisite; JOB-PUBLISHING-MVP JP78-010 and its 2026-10-27 personal-email bypass **for new Users only** |
| UREG-004–007, 010, 014 | REC-01/REC-02/REC-03 Company creation and dossier journey; retain Company review, one active membership and immutable evidence |
| UREG-015 | REC-03 legacy employer and provisional Company endpoints; retire as section 7 specifies |

AUTH-02 public/Staff portal routing is preserved. Candidate and recruiting workspaces are client navigation choices inside the public portal; no new token role, portal, cookie, dependency or frontend architecture is selected. Existing Company Admin exclusions, job assignment checks and Staff moderation authority remain.

## 2. Shared registration and capability projection

`POST /api/auth/register` retains its JSON request and 201 response with the existing `{status,message,data}` envelope and `UserDto` fields. Required fields are `firstName`, `lastName`, `username`, `email`, `password`. Preserve trimmed names (1–100 characters), username `[a-zA-Z0-9_]{3,50}`, normalized lowercase trimmed email (maximum 255), and current password rule (8–128 characters, uppercase, lowercase and digit). Preserve uniqueness and BCrypt. No Company, gender, phone or account-type field is required or used to grant authority.

Create one ACTIVE platform USER, with `emailVerified=false` and the new rollout marker enabled. Registration issues no verification-email request and no token pair; the User signs in through existing `/api/auth/login`. Preserve 400 `40001 INVALID_INPUT` / `40002 WEAK_PASSWORD` and 409 `40901 USERNAME_OR_EMAIL_EXISTS`, including competing registrations. Platform Staff cannot be created through this endpoint.

`GET /api/auth/me` retains existing fields and adds:

```json
{
  "emailVerified": false,
  "recruitingEmailVerificationRequired": true,
  "recruitingEmailEligible": false,
  "candidateAccessAllowed": true
}
```

`recruitingEmailVerificationRequired` reflects the persisted rollout marker; `emailVerified` remains the stored flag, including historical default values. `recruitingEmailEligible` means active and either legacy-exempt or email-verified. `candidateAccessAllowed` means active; it does not imply implementation of missing Candidate features. These are current persisted state, not authorization claims. Existing `activeMembership` remains additive-compatible. Company status and permissions continue to come from Company services. Never include proof hashes, secrets or private dossier data.

## 3. Explicit account-email verification

These new endpoints require an authenticated active public User, including Users with no Company. Missing/invalid token returns existing 401; wrong Staff/public portal remains 403. An authenticated actor ID and current account email bind every proof; clients cannot supply a destination address.

| Endpoint | Request | Success data |
|---|---|---|
| `POST /api/me/email-verification/request` | No body | `{deliveryAccepted:boolean, alreadyVerified:boolean, nextRequestAt:instant-or-null, expiresAt:instant-or-null}` |
| `POST /api/me/email-verification/confirm` | `{emailSecret:string}` | `{emailVerified:true}` |

Both return 200 envelopes. Already-verified request returns `alreadyVerified:true`, null times and no mail. A legacy-exempt User with a false historical flag may request genuine proof, but remains exempt before and after. Confirmation of an invalid/replayed token never grants authority.

Approved email lifecycle:

- Generate a cryptographically random 256-bit URL-safe secret. Persist only SHA-256 hash, actor, normalized account email, issuance and expiry instants. Do not persist the plaintext token or expose it in API responses/logs.
- Link lifetime is 24 hours from committed issuance; `now >= expiresAt` is expired. Use the existing controllable Clock. Successful confirmation consumes the secret and sets `users.email_verified=true` atomically under the User guard lock. A second confirmation returns 400 `40030 INVALID_EMAIL_PROOF`; concurrent confirmations have exactly one success.
- Resend uses the same request endpoint. Enforce 60 seconds from the last issuance; premature requests return 429 `42930 EMAIL_REQUEST_COOLDOWN` and `Retry-After` in seconds. A committed new issuance immediately invalidates every previous token, including when delivery later fails. Issuance and confirmation serialize on the User guard lock.
- Commit issuance before sending mail through the existing Resend/JDK adapter. A false result, missing configuration or network failure returns `deliveryAccepted:false`; no proof is granted. After the cooldown, the User can request a replacement. A crash after issuance is recoverable by another request. No automatic resend worker or durable plaintext email queue is introduced.
- `deliveryAccepted:true` means provider acceptance, not inbox delivery. Neither provider result affects Candidate access. The request response never contains the link.
- The confirmation URL uses the existing exact-origin allowlist and HTTPS policy with the existing localhost HTTP development exception. Put `emailSecret` in a URL fragment. An owned client must require sign-in and explicit confirmation, clear the fragment, and POST the secret; opening a link or issuing a GET changes no server state. Wrong-account, changed-email, malformed, expired and replayed proofs return the same 400 `40030 INVALID_EMAIL_PROOF`. A wrong-account request does not consume the real recipient's token.
- Account-email changes are outside this feature. No writer may change a new User's email while retaining verified status or usable proof for the former email; block such a writer until its own approved email-change flow exists.

Scoped authorization failures use 403 `40335 EMAIL_VERIFICATION_REQUIRED`; suspended Users preserve 403 `40304 ACCOUNT_SUSPENDED`. These messages remain English technical API text; clients display Vietnamese instructions. Keep the existing envelope, without establishing a project-wide error redesign.

## 4. Complete Company submission and rejected correction

Initial `POST /api/companies/verification-request` becomes `multipart/form-data`: `dossier` is an `application/json` part and `file` contains the registration/license document. This deliberately replaces the old JSON/URL-or-upload-ID contract after callers migrate. The JSON part is:

```json
{
  "name": "Company legal name",
  "taxCode": "Company tax code",
  "legal": {
    "workAddress": "Business address",
    "submitterPosition": "Submitter job title",
    "representation": "LEGAL_REPRESENTATIVE",
    "declarationAccepted": true
  }
}
```

Required trimmed bounds: legal name 1–255; tax code 1–50 (preserve current tax-code matching/duplicate behavior); address 1–1000; job title 1–200. Representation is `LEGAL_REPRESENTATIVE` or `AUTHORIZED_REPRESENTATIVE`. Declaration must be true; snapshot existing server-owned Vietnamese declaration text/version. No separate Company email is accepted; derive the current account email for internal Company contact and decision delivery. Optional logo, website and description are not submission prerequisites; this slice does not introduce profile asset storage or collect those optional fields.

Accept exactly one nonempty PDF/JPEG/PNG up to 10 MiB using existing signature checks, sanitized filename (maximum 255), content hash and private byte storage. Reject malformed parts, missing fields, missing declaration and unsupported files with 400 `40001 INVALID_INPUT`; oversized multipart requests return 413 in the existing envelope. Configure multipart request overhead to accommodate one 10 MiB file plus the bounded JSON fields. No standalone document-upload request precedes submission. A failed request persists no file, unfinished dossier, Company, membership or attempt.

Within one transaction, resolve/lock the current active User; enforce new-User email proof and absence of an active membership; validate everything; insert document, PENDING Company, first active OWNER membership and immutable attempt/declaration association. Existing uniqueness indexes, deferred Owner consistency checks and guard locking remain authoritative. Duplicate tax code returns existing 409 `40901 DUPLICATE_TAX_CODE`; existing membership returns 409 `40910 ACTIVE_COMPANY_MEMBERSHIP_EXISTS` (existing rejected Owner may receive `40902 RESUBMISSION_REQUIRED`). Concurrent requests must yield at most one Company/active membership and no orphan evidence. Return existing 201 `CompanyVerificationResponse`, with existing identity, version and processing/outcome fields.

`PUT /api/companies/me/verification` and `PUT /api/companies/{id}/resubmit` accept the same multipart parts plus required nonnegative `expectedVersion` in `dossier`. Tax code is absent and immutable. Both aliases remain. Only the current Owner of that Company can correct a REJECTED dossier; PENDING/VERIFIED return existing 400 `40012 COMPANY_NOT_REJECTED`. Stale version returns existing 409 `40022 CONCURRENT_MODIFICATION`. Each correction supplies a complete file and fields, inserts new immutable evidence and advances the same Company to PENDING atomically. No edit of a pending attempt or deletion/reuse of old evidence occurs. The new attempt captures its submitting Owner; decision delivery uses that User's current account email through the existing transactional decision intent and retry worker. It does not introduce another Company mailbox or require legacy Users to reverify.

Retain `GET /api/companies/me`, `GET /api/companies/me/verification`, `GET /api/companies/me/verification/history`, Staff review/history routes and authenticated private document downloads with their existing fields and authorization. Own Company and Owner verification responses add `companySuspended` so the scoped suspension/status read exception can identify the blocked prerequisite; public Company/job projections do not gain this private status field. Unknown/unauthorized document access remains non-enumerating 404. Initial submission/correction do not auto-approve a Company. Existing review decisions preserve immutable snapshots and durable decision mail; rejected feedback remains visible to the Owner.

## 5. Operation matrix and invitation behavior

An active legacy User is exempt from the **new personal email gate only**. All current membership, Company verification/suspension, role, assignment and concurrency checks still apply. No phone or obsolete applicant restriction applies to any Candidate/recruiting operation after retirement.

| Operation | Unverified new User | Email-eligible active User |
|---|---|---|
| Sign-in, account state, explicit email request/confirmation, logout/recovery | Allowed under endpoint's own rules | Allowed |
| Candidate profile/CV and implemented Candidate operations | Allowed under feature rules | Allowed under feature rules |
| Open recruiting instructions/form in client | Allowed; no private Company content or persistence | Allowed |
| Initial complete Company submission | 403 email prerequisite | Allowed if no active membership |
| Invitation preview/decline | Existing matching-email/secret rules; no proof granted | Same |
| Invitation acceptance | 403 email prerequisite before mutation | Existing eligible invitation rules |
| Private jobs, members, assignments and invitation management | 403 email prerequisite | Current membership/role/assignment rules; Company suspension blocks |
| Job drafts for PENDING/REJECTED Company | 403 email prerequisite | Allowed where existing JP78 operation permits |
| Job submit/approve/resume/publication | No Company privilege | Existing VERIFIED Company, responsibility, role and deadline rules |
| Own Company identity/status; Owner feedback/history/correction | Current membership/Owner rules; correction requires email eligibility | Same; correction only REJECTED |
| Own historical notifications/read markers | Allowed; following link rechecks access | Allowed; independent of membership departure |
| Public approved job detail | Anonymous existing visibility rules | Same |
| Staff endpoints | Public User denied regardless of workspace | Public User denied regardless of workspace |

Suspended Users cannot access protected operations; public detail and existing anonymous recovery remain under their current rules. Suspended Companies block private job, membership/invitation management, new joining and dossier correction; active Owners may still read minimal Company suspension/status, existing feedback/history and authorized retained evidence. Own inbox and Candidate operations do not acquire Company authority and remain reachable. Legacy exemption does not override suspension.

`POST /api/company-invitations/{preview,accept,decline}` keep existing `{secret,confirm}` requests and response receipts. For first acceptance, check current active User and, if rollout-enabled, verified account email inside the business transaction; then require normalized email match, valid secret, explicit confirmation, PENDING/unexpired invitation, VERIFIED non-suspended Company and absence of an active membership. Use current guard/Company/invitation locks. Denials leave invitation and memberships unchanged. Email mismatch and invalid/expired/revoked secrets remain existing 404 `40410 INVITATION_UNAVAILABLE`; membership conflict remains 409 `40910 ACTIVE_COMPANY_MEMBERSHIP_EXISTS`. First success is 201; existing accepted receipt retry remains 200 and never re-admits a departed member. Preserve revocation, decline and notification semantics. Preview/possession never marks email verified.

Enforce new private-recruiting email rules in service entry points using persisted User state, as well as scoped request checks. Omitting a portal header or selecting another workspace cannot bypass them. Staff endpoints use current platform authority, and a Staff identity gains no Company authoring authority through its role. No User's email state unpublishes an existing approved job.

## 6. Additive schema and deployment contract

One new Flyway migration, with the next unused version at implementation time:

1. Add `users.recruiting_email_verification_required BOOLEAN NOT NULL DEFAULT FALSE`; backfill existing rows false regardless of `email_verified` or employer profile. After that backfill, change its database default to TRUE, so every subsequently inserted User is explicitly rollout-enabled, including insertions by an older writer. Do not infer cohort from timestamps, IDs, Company status or employer profiles. Do not rewrite existing verification flags.
2. Add `user_email_verification` keyed by `user_id` (FK users), with normalized `email VARCHAR(255) NOT NULL`, nullable `secret_hash VARCHAR(64)`, `issued_at TIMESTAMPTZ NOT NULL`, `expires_at TIMESTAMPTZ NOT NULL`, and nullable `consumed_at TIMESTAMPTZ`. Add checks for expiry after issuance and consumed proof having no live hash. One row per User supports replacement under the existing User guard lock; no raw proof is stored.

No Company schema change is needed: existing document, attempt, declaration, Company and membership structures hold the complete submitted data. Preserve all old onboarding tables/records and immutable Flyway migrations. Do not introduce dependencies or test infrastructure.

Deployment is coordinated: stop **all** old account/Company writers before migration, apply migration, deploy new backend and migrate clients before reopening registration/submission. The new database default alone cannot make old code safe: old registration writes `email_verified=true` and would still bypass proof. Do not run a mixed fleet or roll back to an old registration writer with registration enabled. Existing seed/import writers must explicitly declare intended legacy fixtures; normal new accounts must be marked true and start unverified.

Rollback leaves additive columns/table and retained evidence intact. If reverting backend binaries, disable registration, Company submission, invitation acceptance and private recruiting entry points until forward repair; the old binary cannot enforce this contract. No destructive down migration, data reset, automatic promotion of incomplete registrations or restoration of obsolete access restrictions is authorized.

## 7. Retirement and caller inventory

Retire after replacement slices and owned examples migrate; tombstones return 410 with `{status:410,message:"41030 ONBOARDING_RETIRED: Use shared registration and Company setup",data:null}` and no mutation/delivery. Public legacy registration routes retain anonymous reachability to receive 410. Protected tombstones still require authentication; no retained secret can promote a pending employer registration.

| Retired contract | Replacement |
|---|---|
| `POST /api/employer-registrations` | Shared `/api/auth/register` |
| `POST /api/employer-registrations/{id}/email/resend` and `/email/verify` | Sign in to a shared User, explicitly request/confirm account email |
| `GET /api/me/employer-onboarding` | `/api/auth/me` plus existing Company status APIs |
| `POST /api/me/employer-onboarding/email/send` and `/email/verify` | `/api/me/email-verification/request` and `/confirm` |
| `POST /api/me/employer-onboarding/phone/send` and `/phone/verify` | Removed prerequisite; no replacement OTP flow |
| `PUT /api/me/employer-onboarding/company` | No provisional-save replacement |
| `POST /api/me/employer-onboarding/documents` | File part of complete Company submission/correction |
| JSON-only `POST /api/companies/verification-request` and resubmission PUTs | Multipart complete dossier on those same routes; legacy JSON receives 410 |

Concrete code inventory: `EmployerOnboardingController`, `EmployerRegistrationService`, `PersonalVerificationService`, `OnboardingWebConfiguration`/`OnboardingAccessPolicy`, `LegalDossierService` upload/register/correct and its profile-based legacy fallback, `VerificationDocumentController`, `CompanyController`, `CompanyService`, `RecipientInvitationService`, `AuthService`, and job eligibility. Remove exclusive obsolete behavior while preserving shared secrets/mail/Company access, private download, review evidence, delivery and membership components.

Owned callers found in this checkout are `backend/http/auth.http`, `backend/http/company-onboarding.http`, `backend/http/company-membership.http`, `backend/http/job-drafts*.http` and the onboarding/README/API requirement documentation. Update examples and remove obsolete operational instructions. Older documentation mentions a bundled HTML/JS client, but no such source or frontend package is present here. No external Vite client is silently included in completion claims. Supply its repository separately if client implementation is required; this worktree can verify backend contracts and HTTP journeys only. Client migration requires shared registration, workspace navigation, explicit email request/confirmation and multipart Submit without autosave, then continuation of intended invitation/Company/job operations.

Preserve incomplete `employer_registrations`, enrolled Users, Companies, memberships, jobs and evidence. Incomplete registrants must register through the shared endpoint subject to current email uniqueness; no automatic promotion, deletion or new retention policy. Previously enrolled Users are existing Users and keep the legacy exemption while regaining Candidate access.

## 8. Approved verification seams and acceptance cases

The user approved these public verification seams with the contract on 2026-10-28, satisfying the tdd skill’s requirement for agreed test boundaries:

| Seam | What it catches; limits |
|---|---|
| HTTP controllers with actual security and service behavior | Payload/status/envelope, persisted-user gates, portal/workspace isolation and private/public projection; not browser interaction |
| Public business service APIs with controlled Clock and isolated delivery adapter | Email issuance/expiry/replay/delivery recovery and state transitions; not real mailbox delivery |
| Existing PostgreSQL test setup through application service/HTTP entry points | Migration compatibility, atomic rollback, unique constraints and competing registration/submission/acceptance/confirmation; not production deployment |
| HTTP-example critical journeys | Shared registration to Candidate access, proof to Company/Staff review/correction, invitation and draft/publication gating; not absent frontend UI |

For tickets 02–06, implement red→green vertical slices at these boundaries. Include exact expiry/cooldown instants; no automatic registration mail; resend invalidates prior links even on failure; account isolation; single-use confirmation; both legacy flag values remain exempt; new Users cannot bypass private recruiting gates with missing headers; existing applicants regain Candidate access; complete dossier failure/concurrency leaves no orphan data; pending edits rejected; same-Company correction preserves tax code/history; wrong-email/expired/revoked/competing invitation acceptance; membership removal and job-assignment boundaries; pending/rejected versus verified Company drafting/submission; Staff isolation; historical inbox access; private evidence non-enumeration; every tombstone without mutation. Use existing PostgreSQL tooling, no new dependency.

Run documented `./mvnw test` and `./mvnw package` after runtime implementation; affected client checks require its source. This contract-only ticket has no executable change, so runtime tests are not evidence of its approval or completion.
