# [GW-SR-ADM-02] Staff Account Management

| Field | Value |
|---|---|
| Specification ID | GW-SR-ADM-02 |
| Ticket | GW-92 |
| Version | 0.1.0 |
| Status | Draft — implementation scope approved by the requesting human on 2026-10-30 |
| Owner | GuardWork thesis team |
| Human approver | Requesting human user; explicit session approval on 2026-10-30 |
| Last updated | 2026-10-30 |
| Source | [Staff specification](../../feat/staff/spec.md), UC-18–20, ADM-ACC-01–04 and ADM-BAN-01–08 |

## 1. Scope and approval

Implement Staff inspection, banning, and unbanning of users and Companies in the existing Spring MVC backend. This repository currently contains no frontend application; UI delivery requires a separately identified frontend repository and its existing conventions. The API exposes action eligibility for that UI. Backend completion alone does not claim the interface acceptance scenarios are complete.

The requesting human explicitly approved this contract and its scoped schema/authentication changes on 2026-10-30. The agent retains the Draft lifecycle label; this approval does not approve other Staff features.

This document defines the previously unresolved privacy, authorization, API, persistence, expiry, and session-consistency contracts. It does not approve itself or the remainder of the Draft Staff specification. The scoped implementation approval covers the migrations and authentication changes below. Preserve existing Company verification and membership contracts. No new dependencies, Redis, Kafka, or distributed transaction mechanism are proposed.

## 2. Authority and permitted information

Every endpoint requires the existing Staff portal rules and a currently active persisted Platform `STAFF` user. Company `OWNER`, `ADMIN`, and `RECRUITER` membership grants no Platform authority. Recheck the actor inside each mutation transaction.

Staff may inspect all persisted user accounts and Companies, including their own account and other Staff accounts. A Staff member may ban another Staff account but never their own account. Company self-ban means no additional restriction: the prohibition applies to the actor's user ID. This peer-Staff authority is included in the scoped session approval.

Return explicit DTO allowlists, never serialized JPA entities:

| Target | Permitted fields |
|---|---|
| User | `id`, `username`, `firstName`, `lastName`, `email`, `emailVerified`, `role`, `status`, `createdAt`, `updatedAt` |
| Company | `id`, `name`, `taxCode`, `verificationStatus`, `isBanned`, `createdAt`, `updatedAt` |
| Both | `activeBan`, `canBan`, `canUnban` |

`activeBan` is null when no unexpired, unended ban exists; otherwise it contains `id`, `reason`, `startedAt`, `expiresAt` (null means permanent), and `createdBy`. Do not expose passwords, tokens, token hashes, CVs, documents, messages, session IP addresses, or unrelated profile data. Search results use the same allowlist. Eligibility is advisory; writes always revalidate.

## 3. HTTP contract

Reuse existing `ApiResponse` and `PageResponse`. IDs are positive decimal `Long` values. Times are ISO-8601 instants; clients render Vietnamese copy and local time. Backend messages remain technical text.

| Method | Route | Behavior |
|---|---|---|
| GET | `/api/staff/accounts/users` | Search users |
| GET | `/api/staff/accounts/companies` | Search Companies |
| GET | `/api/staff/accounts/users/{id}` | Inspect a user |
| GET | `/api/staff/accounts/companies/{id}` | Inspect a Company |
| POST | `/api/staff/accounts/users/{id}/ban` | Ban a user |
| POST | `/api/staff/accounts/companies/{id}/ban` | Ban a Company |
| POST | `/api/staff/accounts/users/{id}/unban` | End a user ban |
| POST | `/api/staff/accounts/companies/{id}/unban` | End a Company ban |

Search parameters: optional `q` (trimmed, at most 200 characters), `page` (default 0, nonnegative), and `size` (default 20, range 1–100). Blank `q` lists all targets of that type. Use case-insensitive literal substring matching across username/email/first name/last name for users and name/tax code for Companies; a positive numeric query additionally matches ID. Treat SQL wildcard characters literally. Sort by ID descending with stable pagination. No arbitrary sort or role/status filters are introduced.

Ban body: `{ "reason": "...", "expiresAt": null }`. Reason is required, trimmed, 1–2000 characters. Null or omitted expiry means permanent; a temporary expiry must be strictly after server transaction time. Unban body: `{ "reason": "..." }` with the same reason constraint. Reject unexpected fields on these new mutation payloads to avoid silently accepting authority or state supplied by clients. Successful mutations return HTTP 200 with the resulting detail DTO, only after commit.

Use the existing error envelope and local exception handling: 400 for invalid parameters/body, 401 for missing/invalid/revoked credentials, 403 for insufficient authority or self-ban, 404 for a missing target, 409 for already-banned/not-banned/ineligible state or concurrent modification, and 503 for unavailable enforcement storage. Do not return partial success. Preserve existing endpoint identifiers. The scoped account error catalogue is below; the numeric suffix identifies the error family. Target/type/action not-found variants share the 40492 family.


| HTTP | Code / message identifier | Meaning |
|---|---|---|
| 400 | `40092 INVALID_ACCOUNT_INPUT` | Invalid account query, ID, reason, duration, or payload |
| 403 | `40392 SELF_BAN` | Staff attempted to ban their own user account |
| 404 | `40492 ACCOUNT_NOT_FOUND` / `ACCOUNT_TYPE_NOT_FOUND` / `ACCOUNT_ACTION_NOT_FOUND` | Missing target or unsupported route value |
| 409 | `40992 ACCOUNT_STATE_CONFLICT` | Ineligible state or concurrent account enforcement |
| 503 | `50392 ENFORCEMENT_UNAVAILABLE` | Mandatory enforcement storage unavailable |

Existing authentication/Staff authority identifiers remain unchanged. Job moderation
uses its local `40992 POSTING_STAFF_SUSPENDED` conflict reason when approval would
bypass a retained suspension. The frontend integration guide documents the account
flow and error-handling responsibilities.

## 4. Ban lifecycle and persistence

New Flyway migrations `V2026.10.30.92` and `V2026.10.30.93` add:

- Extend the user-status constraint to permit managed `BANNED` while retaining `ACTIVE`, `SUSPENDED`, and `DELETED`.
- `users.session_version BIGINT NOT NULL DEFAULT 0`, constrained nonnegative.
- `account_bans`: generated ID; nullable `user_id` and `company_id` foreign keys with exactly one present; `reason`; `started_at`; nullable `expires_at`; `created_by` user foreign key; nullable `ended_at`, `ended_by`, and `end_reason`. Expiry, when present, must exceed start. At most one unended ban per target via partial unique indexes. End reason and actor are required for manual unban; automatic expiry uses an explicit system end reason with no fabricated Staff actor. Keep ended history. Do not cascade-delete it.
- `job_postings.staff_suspended BOOLEAN NOT NULL DEFAULT false`, independent of the existing publication lifecycle and versioning.

Retain `users.status` and `companies.is_banned` as the materialized enforcement state. User banning requires `ACTIVE`; Company banning requires `is_banned=false`. Do not unban unrelated inactive states or a legacy banned target lacking a managed ban. Such legacy records remain restricted and return conflict for manual unban until reconciled through a separately approved data repair. Existing data is not silently assigned invented reasons, actors, or durations.

Ban sets the user to `BANNED` or Company flag to true. Manual unban ends the current active ban and restores the user to `ACTIVE` or Company flag to false. Neither operation edits Platform roles, membership roles/statuses, or verification outcomes.

Temporary bans stop restricting access at `expiresAt`. A scheduled database worker settles elapsed bans and restores their materialized state. Authentication and Company access also settle elapsed bans transactionally before deciding eligibility, so scheduler delay does not extend the restriction. Detail/search reads calculate the effective state without writing, preserving ADM-SEC-03. A manual unban of an already expired ban returns conflict. A new ban may settle the elapsed previous ban in its write transaction before checking eligibility.

Expiry preserves revoked-session state and posting suspension, exactly as manual unban does. Expiry completion is attributable to the system in ban history; use the existing audit mechanism for Staff actions without inventing a Staff identity for the worker.

## 5. Session invalidation and concurrency

Include `sessionVersion` in newly issued access JWTs. The JWT filter checks the persisted user's current role, effective active state, and session version on every protected request. Missing users, inactive users, or mismatched versions are unauthenticated. A legacy JWT without the claim is treated as version zero, preserving its original expiry while allowing the first enforcement event to invalidate it. Portal restrictions remain unchanged. Database failure must fail closed with 503, never fall back to trusting stale JWT claims.

Login and refresh lock the user row, recheck effective state, and issue credentials using the current version. User ban and unban increment that version and revoke all refresh tokens for the user in the same PostgreSQL transaction as state, history, and audit changes. Thus tokens from before or during the ban cannot become valid again after unban or expiry. A banned user cannot log in or refresh.

Company ban and unban increment session versions and revoke refresh tokens for all currently active Company members (`OWNER`, `ADMIN`, `RECRUITER`). Their personal accounts remain `ACTIVE`; they can sign in again for permitted personal functions, while Company operations remain blocked by the existing Company enforcement policy. Unban invalidates sessions created during the Company ban, requiring another sign-in. Company membership changes during a ban must share the Company lock; new members receive no Company authority while it is banned.

Use one database transaction for target state, ban history, session invalidation, posting suspension, and immutable audit recording. Failure of any mandatory step rolls back all changes. No asynchronous mandatory revocation is permitted. Recheck current persisted actor authority before commit. Acquire Company rows before member user rows, user rows in ascending ID order, then posting rows in ascending ID order; establish the same ordering in affected membership/publication writers. Concurrent opposing Staff operations must serialize or return 409, without duplicate live bans or partial invalidation. A request authorized before a ban commits may finish; subsequent authorization checks must enforce the committed ban.

## 6. Company visibility and posting suspension

Company ban marks all currently `PUBLISHED` postings `staff_suspended=true` and advances their optimistic versions. Retain the existing publication status and content: the current database permits no `SUSPENDED` status, and enforcement is independent of author publication lifecycle. The scoped session approval includes this representation of ADM-BAN-02.

Public posting reads require both a non-banned Company and `staff_suspended=false`, along with existing publication/deadline conditions. Apply the Company ban filter to every existing public Company/search path. Do not add an unrelated discovery endpoint just to satisfy this filter. Publication/resume/review approval and ban transactions share the Company lock so a racing write cannot expose a banned Company's posting. Staff suspension must remain effective through existing publication and resume actions.

Company unban or expiry never clears `staff_suspended`, republishes content, verifies the Company, or grants membership. Restoring suspended postings belongs to UC-21 and is outside GW-92. Public visibility tests must prove that unban alone leaves those postings hidden.

## 7. Audit, rollout, and verification

Record manual ban/unban through existing append-only `audit_logs`, with the authenticated actor, target type/ID, reason, request metadata, and allowlisted before/after enforcement state. Do not put passwords, session credentials, or unrelated personal data into audit payloads or diagnostic logs.

Apply the new migration before deploying code that reads its columns. Stop old token issuers and old API instances during rollout: mixed versions would bypass session-version checks. Existing version-zero JWTs retain compatibility only until their user's version changes. Document this authentication behavior amendment alongside the existing auth contract, plus an ADR after approval. No destructive database reset or historical migration edit is authorized.

Verification will use existing tooling and PostgreSQL test infrastructure:

1. Service tests for field allowlists, literal search, eligibility, self-ban, peer-Staff policy, duration/reason validation, history, and expiry boundaries.
2. HTTP tests for authentication, persisted role enforcement, Company-role separation, portal behavior, response shape, retryable errors, and missing targets.
3. Database tests for fresh/upgrade migration, constraints, concurrent bans, ban versus login/refresh/publication/membership changes, and complete rollback when audit or revocation fails.
4. Token tests proving immediate rejection of old access/refresh tokens, legacy claim compatibility, database-failure denial, and no token resurrection after unban/expiry.
5. Company tests proving all active member roles are invalidated, public postings become hidden, and unban preserves suspension, verification, and membership.
6. Run targeted tests during implementation and the full backend test suite at the end, plus packaging for migration/configuration changes. Review the completed implementation with the requested code-review skill. Frontend/typechecking checks are unavailable in this repository and must be reported accordingly.

Approval of this contract authorizes its scoped schema additions and authentication/enforcement behavior. It does not authorize remote push, a PR, new dependencies, UC-21 restoration, or unrelated Staff features.
