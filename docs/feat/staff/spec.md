# Staff Management — Software Requirements Specification

| Field | Value |
|---|---|
| Specification ID | GW-SR-ADM |
| Project | GuardWork |
| File | `docs/feat/staff/spec.md` |
| Version | 1.5.0 — scoped GW-93 job hiding and event history |
| Status | Draft |
| Owner | GuardWork thesis team |
| Human approver | Not recorded; formal approval remains pending |
| Last updated | 2026-10-31 |
| Product source | Retained UC-11–21 in [usecase.md](usecase.md), scoped by GW-81 |

## 1. Purpose and scope

This draft covers account management, Company verification dossier review, receipt and inspection of user reports, and Job Posting moderation. UC-11–20 retain their existing detailed requirements; UC-21 retains its draft use-case description and awaits detailed contract alignment. UC-22 adds a scoped personal activity-history draft with product decisions confirmed on 2026-10-30; its API proposal awaits review. System monitoring, background-task operations, and platform-wide RBAC design are excluded from this Staff documentation set. GW-81 includes the user-approved runtime rename of the Platform role and implemented verification API namespace. Committed migrations remain immutable; other feature behavior is unchanged.

The actor is Platform Staff with Platform role `STAFF`. Platform authority remains distinct from Company Workspace membership (`OWNER`, `ADMIN`, `RECRUITER`). The user approved runtime adoption of `STAFF` and `/api/staff/**` on 2026-10-24. Section 4.3 records that scoped contract amendment and compatibility impact; it does not approve the remaining Draft capabilities. Login is a prerequisite. Viewing a job is navigation context for hiding, history, and restoration; standalone job viewing and job closure are not separate features here.

| Use case | Capability | Requirement family | Existing planning priority |
|---|---|---|---|
| UC-11 | View report list | ADM-REP | P1 |
| UC-12 | View report details | ADM-REP | P1 |
| UC-13 | Hide Job Posting | ADM-JOB | P1 |
| UC-14 | View posting history | ADM-JOB | P1 |
| UC-15 | View Company verification dossier | ADM-VER | P0 |
| UC-16 | Approve Company verification dossier | ADM-VER | P0 |
| UC-17 | Reject Company verification dossier | ADM-VER | P0 |
| UC-18 | View account information | ADM-ACC | P0 |
| UC-19 | Ban account | ADM-BAN | P0 |
| UC-20 | Unban account | ADM-BAN | P0 |
| UC-21 | Republish hidden Job Posting | ADM-JOB / use-case draft only | Not assigned |
| UC-22 | View own activity history | ADM-ACT / GW-SR-ADM-03 | Not assigned |

Audit recording remains a prerequisite for state-changing actions. Priorities are retained planning context, not new delivery commitments or implementation claims.

### 1.1 Source precedence and compatibility

The user’s revised flows define this document’s selected product scope. Approved Company verification, authentication, onboarding, and membership contracts continue to constrain implementation. Concise use-case wording does not remove existing safeguards such as reviewer attribution, optimistic concurrency, atomic auditing, and field-level privacy.

Stable requirement identifiers are retained where the behavior remains applicable. IDs of former report-resolution requirements are retired from this selected scope rather than reused for unrelated reads. New read/history requirements use new identifiers. Outside the user-approved role and route amendment in Section 4.3, this draft does not authorize new endpoints, schema, infrastructure, dependencies, role hierarchy, error envelope, or notification architecture.

## 2. Common rules and state meanings

| ID | Rule |
|---|---|
| ADM-SEC-01 | Every operation shall require an authenticated Platform Staff member and enforce authority on the server, including requests made without the UI. |
| ADM-SEC-02 | Read operations shall return only information within the authorized target scope. Passwords, password hashes, tokens, and unrelated private communications shall not be exposed. |
| ADM-SEC-03 | A read shall not change business status, apply a sanction, or imply a decision. |
| ADM-SEC-04 | Every mutation shall recheck current target state and record the responsible Staff. A failed mandatory step shall not be reported as a successful operation. |

| Subject | States / meaning |
|---|---|
| Company verification | `PENDING` → `VERIFIED` or `REJECTED`; later Owner correction/resubmission is governed by the existing Company workflow. |
| Verification presentation | `PENDING` derives processing `PENDING` and no review outcome. `VERIFIED/REJECTED` derive processing `CHECKED` and the corresponding outcome. `CHECKED` does not confer approval. |
| Job hiding | Public `PUBLISHED` → `SUSPENDED`; retain content and history. |
| User enforcement | `ACTIVE` → `BANNED` → applicable active state after unban. |
| Company enforcement | Non-banned/banned is represented by the existing Company flag `is_banned`, independently of verification. |
| Reports | Display the stored status, including existing `OPEN`, `IN_REVIEW`, `RESOLVED`, and `REJECTED` values; no transitions are introduced by viewing. |

The source UC 13 used `PUBLISHING` in its precondition and `PUBLISHED` in alternative 4a. `PUBLISHED` is used consistently here to match the existing Staff terminology. This correction does not introduce a new lifecycle state. Company active/banned wording similarly denotes enforcement eligibility rather than a new Company status column.

## 3. Functional requirements

### 3.1 UC-11–12 — View Reports

Preconditions: the common conditions hold; details require an existing accessible report. Postcondition: list/details are displayed without changing report state or applying sanctions.

| ID | Requirement |
|---|---|
| ADM-REP-01 | The system shall display report summaries and permit filtering by stored report status and target type (`COMPANY`, `CANDIDATE`, `JOB`). Summaries shall include target information, complaint content, submission time, and current status. |
| ADM-REP-08 | Selecting a report shall display complaint content, permitted reporter information, reported target, evidence, current status, and an existing processing outcome when present. |
| ADM-REP-09 | No reports or no matching reports shall produce an explicit empty/no-results view. Retrieval failure shall be reported separately and permit retry. |
| ADM-REP-10 | Missing or inaccessible reports shall not be displayed. Unavailable evidence shall be identified while the remaining retrievable details remain visible. |
| ADM-REP-11 | Viewing the list or details shall not begin processing, acquire a processing lock, resolve/reject a report, or automatically apply sanctions. |

Former `ADM-REP-02–07` described review locks, resolution concurrency, sanction selection, and terminal-state updates. They are outside the user’s selected read-only scope and are not acceptance requirements for UC-11–12. Existing outcomes remain readable. This revision does not authorize removal of another approved product workflow.

### 3.2 UC-13–14 — Hide Job Posting and View History

GW-93 scoped approval (2026-10-31): the requesting maintainer approved the focused [Staff job management contract](../../software-requirements/adm/staff-job-management-contract.md), including API, additive schema, visibility and concurrency boundaries. This section is implemented under that approval; the rest of this document retains its Draft status.

Preconditions: hiding requires a public `PUBLISHED` posting; history requires an authorized posting context. Postconditions: successful hiding produces `SUSPENDED` and removes public visibility; history inspection changes nothing.

| ID | Requirement |
|---|---|
| ADM-JOB-01 | The Staff shall be able to hide a public `PUBLISHED` posting by supplying a mandatory reason. |
| ADM-JOB-02 | After rechecking authority and current state, the system shall set `SUSPENDED` and retain the actor, time, and reason. Existing version safeguards shall remain applicable; this draft does not remove them. |
| ADM-JOB-03 | A hidden posting shall be removed from related public-content caches. |
| ADM-JOB-04 | A hidden posting shall no longer appear in public display or search results. Search/cache mechanisms require an approved integration contract. |
| ADM-JOB-05 | A Candidate following the hidden posting’s link shall receive a suspended/unavailable indication. Missing reason or a non-public current state shall prevent hiding. |
| ADM-JOB-06 | Hiding shall record an attributable audit entry and preserve posting content/history for authorized tracing. |
| ADM-JOB-07 | The Staff shall be able to view recorded posting-history events, including time, actor, action, status, and reason where available, and return to the posting. |
| ADM-JOB-08 | History inspection shall not edit events or content. Empty history and retrieval failure shall be displayed distinctly. |

Beyond the focused contract’s approved `SUSPENDED` extension, this scope adds no further publication states, job closure, physical deletion, or access to unrelated applicant data and Company communications.

### 3.3 UC-15–17 — Company Verification

Preconditions: dossier viewing requires an existing dossier; decisions require `PENDING`. Postconditions: viewing leaves status unchanged; a successful decision records `VERIFIED` or `REJECTED` with attributable history and the applicable notification intent.

| ID | Requirement |
|---|---|
| ADM-VER-01 | The system shall provide a paginated verification queue defaulting to `PENDING`, ordered by creation time ascending, with filters `PENDING`, `VERIFIED`, `REJECTED`, and `ALL`. |
| ADM-VER-02 | The system shall display Company name, tax code, stored contact details, verification state, and existing decision/reason. For enhanced onboarding it shall also display retained submissions, address, submitter position, representation, declaration, and legal documents. Staff members may download accessible evidence. Legacy dossiers shall display only actually retained data. |
| ADM-VER-03 | The Staff shall be able to approve a `PENDING` dossier. The system shall set `VERIFIED`, retain reviewer/time, increment the version, and preserve the approved decision contract. |
| ADM-VER-04 | Rejection shall require a reason of 10–1000 characters after trimming. The system shall set `REJECTED`, retain reason, reviewer/time, increment the version, and update a retained submission outcome where present. |
| ADM-VER-05 | A decision notification shall be addressed to the user who submitted the verification request. For enhanced retained submissions the existing implementation shall record a delivery intent in the decision transaction and dispatch email asynchronously after commit. Failed delivery shall not undo a committed decision. Legacy URL-only dossiers without retained attempts remain a known notification gap, not a fulfilled delivery guarantee. |
| ADM-VER-06 | The decision shall record an immutable audit entry, including actor, request context, and before/after data, in the same transaction. A failed transaction shall not leave a successful decision. |
| ADM-VER-07 | Decisions shall require the current `expectedVersion`. A stale version or competing update shall be rejected without overwriting another decision. Non-pending dossiers shall receive an invalid-state response rather than a new decision. |
| ADM-VER-08 | Empty queues shall be displayed explicitly; missing/inaccessible evidence shall not be downloaded; already-reviewed dossiers shall show their outcome without decision controls. |
| ADM-VER-09 | After a successful decision the interface shall reload the list. On error the system shall report the cause; automatic conflict reload is not claimed by these use cases or the current interface. |

Approval does not introduce a separate badge service, search-ranking boost, automatic external-registry check, or expanded communication permission. Those older reference-document claims are not established by UC-15–17. Existing Company eligibility rules continue to apply.

### 3.4 UC-18 — View Account Information

Precondition: the target exists, subject to the common authorization conditions. Postcondition: information is displayed without changing status or access rights.

| ID | Requirement |
|---|---|
| ADM-ACC-01 | The Staff shall be able to search for and select a user account or Company, and inspect permitted identifying data, current state, and ban information. |
| ADM-ACC-02 | The interface shall offer Ban or Unban when the corresponding conditions are met. |
| ADM-ACC-03 | Missing/inaccessible targets shall not be displayed. Retrieval errors shall be reported and permit retry. |
| ADM-ACC-04 | Credentials, password hashes, tokens, and unrelated private communications shall not be displayed. |

Exact search fields, private-field visibility, and pagination are defined by the
[GW-92 scoped account contract](../../software-requirements/adm/account-management-spec.md),
explicitly approved by the requesting human on 2026-10-30. This approval covers
UC-18–20 only and does not approve the remaining Draft Staff capabilities.

### 3.5 UC-19–20 — Ban and Unban

Preconditions: banning requires an existing active target; unbanning requires a currently banned target. Postconditions: the enforcement state changes with required session invalidation and an audit record.

| ID | Requirement |
|---|---|
| ADM-BAN-01 | The Staff shall be able to ban an active user account or non-banned Company with a reason and a temporary or permanent duration. The system shall validate authority, target, state, and duration. Self-ban shall be refused. |
| ADM-BAN-02 | The system shall retain banned state, reason, and duration, and revoke related sessions. A Company ban shall also suspend public postings, revoke related recruiters’ sessions, and hide the Company from public search. |
| ADM-BAN-03 | A ban shall not be confirmed as successful unless required session revocation has succeeded. Implementation must define consistent failure/rollback behavior; this use-case revision does not select a distributed transaction mechanism. |
| ADM-BAN-04 | Access restrictions shall remain effective for the applicable ban duration. The Staff use cases do not select a Redis key format, TTL, or fallback architecture. |
| ADM-BAN-05 | The Staff shall be able to unban a currently banned target by entering a mandatory unban reason. Missing targets, no active ban, or missing reasons shall prevent success. |
| ADM-BAN-06 | Unbanning shall end the active ban, restore the applicable active/non-banned state and permitted access, and keep sessions issued before or during the ban invalid. The user shall sign in again. Failed mandatory invalidation shall not be confirmed as a successful unban. |
| ADM-BAN-07 | Ban and unban shall record attributable audit entries with the target’s before/after state. |
| ADM-BAN-08 | Restored access shall remain subject to existing role, membership, and verification conditions. Unbanning shall not grant a new role, verify a Company, or automatically republish suspended postings. |

### 3.6 Audit safeguards supporting the selected use cases

| ID | Requirement |
|---|---|
| ADM-AUD-01 | State-changing administrative actions in this scope shall create an attributable audit record. Read-only list/detail/history views do not imply mutations. |
| ADM-AUD-02 | Company verification audit records shall join the decision transaction. Other mutations require a scoped consistency contract before implementation. |
| ADM-AUD-03 | Existing append-only audit protections shall remain intact. This documentation change shall not weaken or change committed audit persistence guards. |
| ADM-AUD-05 | Audit or review responses shall not bypass the common privacy restrictions. |

Former `ADM-AUD-04` general audit-log search remains retired and outside scope.
The separately scoped UC-22 below uses new ADM-ACT identifiers for the caller's own
history only; it does not restore cross-Staff audit search.

### 3.7 UC-22 — View Own Activity History

The requesting human confirmed a read-only personal reconciliation view on
2026-10-30. [GW-SR-ADM-03](../../software-requirements/adm/personal-activity-history-spec.md)
owns ADM-ACT-01–13 and the complete acceptance criteria. Its
[API proposal](../../software-requirements/adm/personal-activity-history-implementation-contract.md)
is Draft and requires technical review before implementation.

The actor may see only their own committed account-management, Job Posting
management, and Company-verification changes, including retained historical audit.
Display only safe recorded time/action/target/state/reason fields. Missing facts
remain unavailable. Support Vietnam-date, group, action, and exact target-code
filters; default to 30 local calendar dates including today, newest first, with
20-row pages. Permit no edit/delete/hide/export/raw-JSON or target navigation.
Existing reviewer attribution in authorized target histories remains unchanged.

## 4. Contracts and implementation status

### 4.1 Existing verification contracts

These are existing interfaces, not newly selected routes. Consult the approved verification specification and onboarding implementation documentation for complete payload and compatibility rules.

| Operation | Existing interface / constraint |
|---|---|
| Verification queue | `GET /api/staff/companies/verifications`; default `status=PENDING`, `page=0`, `size=20`; supported statuses include `ALL`; page is non-negative and size is 1–100. |
| Decision | `PUT /api/staff/companies/{id}/verify`; `status` is `VERIFIED` or `REJECTED`, current `expectedVersion` is required, rejection reason is 10–1000 trimmed characters. |
| Rejection convenience route | `PUT /api/staff/companies/{id}/reject`; existing rejection reason and version constraints remain applicable. |
| Retained dossier history | `GET /api/staff/companies/{id}/verification-history`; existing Platform Staff access applies. |
| Private evidence | `GET /api/company-verification-documents/{id}`; authenticated authorized download, no-store/nosniff response. |

| Existing verification failure | HTTP | Current service reason code |
|---|---|---|
| Missing Company | 404 | `40400 COMPANY_NOT_FOUND` |
| Company no longer pending | 400 | `40010 COMPANY_NOT_PENDING` |
| Invalid rejection reason | 400 | `40011 INVALID_REJECTION_REASON` |
| Missing expected version / invalid request | 400 | `40000 BAD_REQUEST` |
| Version mismatch / concurrent update | 409 | `40022 CONCURRENT_MODIFICATION` |

The service checks non-pending state before version mismatch, so a previously completed dossier may return the invalid-state response rather than conflict. The interface reloads the queue on successful approval/rejection; it currently displays errors without an automatic conflict refresh. Keep authentication and response-envelope behavior under the existing contracts.

Enhanced retained-submission decisions create persisted email intents for the submitting user, processed by the existing delivery worker. Provider acceptance is not proof of mailbox arrival. Missing configuration or exhausted retries remain operational failures; legacy dossiers without an enhanced attempt do not currently create an intent. See [onboarding delivery documentation](../../backend/employer-onboarding.md).

### 4.2 Not yet implemented or specified

The repository does not currently provide the Staff report-list/detail, job restoration, or account lookup/ban/unban interfaces described here. GW-93 adds job hiding and posting-event history under its separately approved focused contract. Existing session/authentication helpers do not establish those complete workflows. UC-21 remains a use-case draft; its detailed requirements, publication eligibility, concurrency, and failure recovery must be aligned before implementation.

Older versions of this draft contained proposed report-resolution, account, job, and audit-search endpoints, SQL schemas, Redis keys, Kafka topics, and search integrations. They are not treated as implemented or approved contracts in this revision. No deployed API removal, schema migration, dependency change, or architecture selection is authorized by their removal from the active draft. Concrete contracts for unimplemented capabilities must be proposed and approved separately.


### 4.3 GW-81 runtime adoption and compatibility

The user approved changing the implemented Platform Admin role and APIs to Staff in the GW-81 conversation on 2026-10-24. This section records that scoped amendment to the earlier authentication/verification contracts; the remaining functional specification stays Draft. The initial GW-81 documentation boundary retained external examples. In a subsequent instruction on 2026-10-24, the user authorized synchronizing those documents with Staff terminology while explicitly preserving Company Admin. Current Platform examples use `STAFF` and `/api/staff/**`; Company membership rules remain authoritative and unchanged.

| Implemented operation | Current API | Use-case coverage |
|---|---|---|
| Verification queue | `GET /api/staff/companies/verifications` | UC-15 dossier selection |
| Approval or rejection decision | `PUT /api/staff/companies/{id}/verify` | UC-16–17 |
| Rejection convenience operation | `PUT /api/staff/companies/{id}/reject` | UC-17 |
| Retained dossier history | `GET /api/staff/companies/{id}/verification-history` | UC-15 |
| Private evidence download (shared route retained) | `GET /api/company-verification-documents/{id}` | UC-15; existing Owner access also preserved |

All four former Admin APIs belong to the retained use cases. No implemented Admin API for monitoring, background-task management, report adjudication, or audit-log search was found, so no unrelated feature API is removed. Employer onboarding, Company membership, Candidate, authentication, and notification APIs retain their behavior and routes.

- **Role and persistence:** `V2026.10.24.81__rename_platform_admin_to_staff.sql` converts existing `users.role = 'ADMIN'` rows to `STAFF` and changes `users_role_check` to allow `USER` and `STAFF`. It does not change `company_memberships.role`, user IDs, verification decisions, or audit history. Flyway applies the migration transactionally; earlier migrations are unchanged. Platform role guards in shared dossier access, onboarding, and the existing legacy-ownership command use `STAFF` to preserve their existing access boundaries. The command is not an additional Staff API or use case.
- **Client compatibility:** old `/api/admin/**` routes have no controller mapping and no compatibility alias. Clients must use `/api/staff/**`. Swagger's `/v3/api-docs` lists the four Staff operations and no Admin route. Existing DTO field names and response envelopes are unchanged.
- **Authentication:** Staff APIs require Bearer authentication with Platform role `STAFF`; authentication and actor attribution do not trust `X-Admin-*` or `X-Staff-*` headers. Old access tokens carrying `ADMIN` cannot access Staff APIs. Sign in again or use the existing refresh flow to obtain a `STAFF` token after migration. Token lifetimes and refresh rotation behavior are unchanged.
- **Company separation:** Company roles remain `OWNER`, `ADMIN`, and `RECRUITER`. Company `ADMIN` with Platform role `USER` cannot use Staff APIs. No Company role is migrated or granted Platform authority.
- **Local fixtures:** the Platform demo account is `demo_staff` / `staff@demo.guardwork.test` with role `STAFF`. Its default password for a newly seeded account remains `GuardWork123!`. `demo_company_admin` / `company-admin@demo.guardwork.test` remains Platform `USER` with Company `ADMIN`. The seed SQL and printed account list use the new Staff identity; existing rows and passwords are preserved on reruns. Previously seeded `demo_admin` accounts are not renamed or deleted by the seed (their Platform role is migrated by Flyway). The backend setup guide uses the current Staff identity and records this legacy-account compatibility.
- **Decision:** rename the existing backend feature package to `staff` and enforce the new role at the existing security boundary; retain verification states, optimistic locking, reviewer attribution, immutable auditing, private evidence safeguards, and decision delivery. No new dependencies or shared authorization architecture are introduced. The decision is recorded here under the user's restriction to Staff documentation rather than creating an ADR outside this folder.

Verification uses the existing isolated PostgreSQL setup: `docker compose -f docker-compose.test.yml up -d --wait`, `./mvnw test`, and `./mvnw package` from `backend/`. The Staff HTTP test checks authentication, Company-role separation, JWT login/refresh claims, decisions and audit attribution, retired routes, and the generated OpenAPI paths. The PostgreSQL migration test verifies conversion of old Platform Admin rows while retaining Company Admin memberships and rejects new legacy Platform `ADMIN` values.

## 5. Scope exclusions and unresolved decisions

| Topic | Boundary / follow-up |
|---|---|
| Login | Remains an authentication prerequisite; excluded as a use case here. |
| Standalone job view and closure | Not included in UC-11–21. Contextual job viewing remains necessary for hiding/history/restoration. |
| Report adjudication | Review locks, warning/ban/takedown sanction selection, resolution/rejection, and resolution concurrency are outside UC-11–12. Do not implement them as side effects of opening details. Wider approved product obligations are not removed by this selected scope. |
| Audit-log search | General/cross-Staff search remains excluded. UC-22 adds only own-history reads/search under the dedicated Draft contract; immutable audit recording remains required. |
| Platform/Company authority | Approved authentication separates Platform role from Company membership. Staff documentation preserves this boundary and does not define a platform-wide role hierarchy. |
| Verification reference | `company_verification.md` includes broader registry/domain/forensics checks, authorization-letter rules, badges, and ranking claims. They are not acceptance requirements introduced by this revision; approved onboarding/verification contracts govern implementation. |
| Job restoration (UC-21) | Retained in the use-case draft only. Reconcile administrative `SUSPENDED` with independent Platform Removal in the Job Publishing Draft, publication eligibility, concurrent decisions, and visibility recovery before implementation. No new API or schema is selected. |
| Search/cache removal | Public hiding must hold across relevant read paths. Technology and cross-system failure behavior remain unselected. |
| Account enforcement | Exact authorization, private-field scope, session invalidation, ban-expiry handling, consistency, and failure recovery require approved implementation contracts. UC-20 does not require automatic republication. |
| Notification coverage | Enhanced attempts have delivery support; legacy decision notification remains a gap. No new global notification architecture is selected. |

These boundaries are explicit gaps, not permission to bypass the stated outcomes or change an approved requirement. The specification remains Draft until formal approval is recorded.

## 6. Acceptance and verification scenarios

| Use case | Minimum verification |
|---|---|
| UC-11 | Report list/filter displays correct target/status summaries; empty and failed loads are distinct; viewing changes no report state. |
| UC-12 | Authorized details/evidence/outcome display; missing/inaccessible reports are refused; unavailable attachments preserve remaining information; no review or sanction starts. |
| UC-13 | Only public postings with a reason are hidden; public display/search/cache access is removed; actor/time/reason and audit persist; content/history remain; hidden links are unavailable. |
| UC-14 | Authorized history includes recorded actors/times/actions; empty and failed loads differ; viewing changes no history/content. |
| UC-15 | Default pending FIFO pagination and all status filters work; enhanced evidence/history and legacy absence are represented accurately; documents enforce access. |
| UC-16 | Pending/current-version approval persists verified status, reviewer/time/version and atomic audit; missing, non-pending, stale, and failed transactions cannot overwrite a decision. |
| UC-17 | Trimmed reasons at 10 and 1000 characters are accepted; below/above limits are refused; pending/current-version rejection retains reason, reviewer/time/version and audit. |
| UC-16–17 delivery | Enhanced decisions create a submitter-addressed delivery intent in the transaction; retries do not repeat the business decision or undo it; legacy delivery gaps remain visible. |
| UC-18 | Authorized target information and eligible actions display; private credentials/messages are excluded; missing/inaccessible targets and retrieval failures are handled. |
| UC-19 | Active user/non-banned Company can be banned with valid reason/duration; self-ban and already-banned targets are refused; Company restrictions cascade; required revocation failure cannot report success. |
| UC-20 | Active ban and reason are required; applicable access returns, old sessions remain invalid, new sign-in is required; Company verification/roles/posting publication are not automatically changed. |
| UC-22 | Own actor scope is enforced for rows and counts; only successful in-scope activities appear; safe historical projection, Vietnam-date filters, 20-row paging, and missing-data/empty/error states apply; no mutation/export/target navigation is offered. |

UC-21 verification scenarios remain pending detailed specification alignment; its draft flow does not establish an approved implementation contract.

GW-81 changes the implemented verification role/routes and their tests. GW-93 adds verification for UC-13/UC-14 under the focused contract. The remaining scenarios guide future implementation verification; tests for unimplemented Staff features are not claimed to pass.

## 7. Related documents

- [Staff use cases](usecase.md)
- [Company verification reference](company_verification.md)
- [Approved Company verification specification](../../software-requirements/rec/company-verification-spec.md)
- [Approved Authentication specification](../../software-requirements/auth/authentication-authorization-spec.md)
- [Confirmed onboarding implementation contract](../../software-requirements/rec/company-onboarding-implementation-contract.md)
- [Onboarding implementation and delivery configuration](../../backend/employer-onboarding.md)
- [Backend architecture and verification commands](../../../backend/README.md)
