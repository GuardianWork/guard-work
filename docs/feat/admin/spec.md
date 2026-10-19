# Admin Management & System Governance — Software Requirements Specification

| Field | Value |
|---|---|
| Specification ID | GW-SR-ADM |
| Project | GuardWork |
| File | `docs/feat/admin/spec.md` |
| Version | 1.2.0 — separate background-task monitoring and triggering |
| Status | Draft |
| Owner | GuardWork thesis team |
| Human approver | Not recorded; formal approval remains pending |
| Last updated | 2026-10-19 |
| Product source | User-revised UC 9–20 and UC-22 in [usecase.md](usecase.md) |

## 1. Purpose and scope

This revision translates the user’s thirteen revised Admin use cases (UC 9–20 and UC-22) into testable functional requirements. It replaces the previous broad Admin draft as the description of this selected scope. It does not change runtime behavior, existing API compatibility, committed migrations, or approved requirements in other documents.

The actor is the System Administrator with Platform role `ADMIN`. The role is distinct from Company Workspace Owner/Admin membership. Login is a prerequisite, not a use case in this revision. Viewing a job remains navigation context for hiding it and inspecting history; standalone job viewing and job closure are not included as separate features.

| Use case | Capability | Requirement family | Existing planning priority |
|---|---|---|---|
| UC-09 | Monitor system health and traffic | ADM-MON | P2 / Post-MVP |
| UC-10 | Monitor background tasks | ADM-CRON | P3 / Post-MVP |
| UC-22 | Trigger background task | ADM-CRON | P3 / Post-MVP |
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

Audit recording remains a prerequisite for state-changing actions. Priorities are retained planning context, not new delivery commitments or implementation claims.

### 1.1 Source precedence and compatibility

The user’s revised flows define this document’s selected product scope. Approved Company verification, authentication, onboarding, and membership contracts continue to constrain implementation. Concise use-case wording does not remove existing safeguards such as reviewer attribution, optimistic concurrency, atomic auditing, and field-level privacy.

Stable requirement identifiers are retained where the behavior remains applicable. IDs of former report-resolution requirements are retired from this selected scope rather than reused for unrelated reads. New read/history requirements use new identifiers. No new endpoint, schema, infrastructure, dependency, role hierarchy, error envelope, or notification architecture is authorized by this draft.

## 2. Common rules and state meanings

| ID | Rule |
|---|---|
| ADM-SEC-01 | Every operation shall require an authenticated Platform Admin and enforce authority on the server, including requests made without the UI. |
| ADM-SEC-02 | Read operations shall return only information within the authorized target scope. Passwords, password hashes, tokens, and unrelated private communications shall not be exposed. |
| ADM-SEC-03 | A read shall not change business status, apply a sanction, or imply a decision. |
| ADM-SEC-04 | Every mutation shall recheck current target state and record the responsible Admin. A failed mandatory step shall not be reported as a successful operation. |

| Subject | States / meaning |
|---|---|
| Company verification | `PENDING` → `VERIFIED` or `REJECTED`; later Owner correction/resubmission is governed by the existing Company workflow. |
| Verification presentation | `PENDING` derives processing `PENDING` and no review outcome. `VERIFIED/REJECTED` derive processing `CHECKED` and the corresponding outcome. `CHECKED` does not confer approval. |
| Job hiding | Public `PUBLISHED` → `SUSPENDED`; retain content and history. |
| User enforcement | `ACTIVE` → `BANNED` → applicable active state after unban. |
| Company enforcement | Non-banned/banned is represented by the existing Company flag `is_banned`, independently of verification. |
| Reports | Display the stored status, including existing `OPEN`, `IN_REVIEW`, `RESOLVED`, and `REJECTED` values; no transitions are introduced by viewing. |

The source UC 13 used `PUBLISHING` in its precondition and `PUBLISHED` in alternative 4a. `PUBLISHED` is used consistently here to match the existing Admin terminology. This correction does not introduce a new lifecycle state. Company active/banned wording similarly denotes enforcement eligibility rather than a new Company status column.

## 3. Functional requirements

### 3.1 UC-09 — Monitor System Health and Traffic

Precondition: the Admin is viewing the monitoring dashboard. Postcondition: visual metrics are available without disrupting the primary database.

| ID | Requirement |
|---|---|
| ADM-MON-01 | The system shall collect and display available hardware, memory, database-connection, and user-traffic health metrics from dedicated monitoring buffers. |
| ADM-MON-02 | The dashboard shall display online-user counts, requests per minute, and error metrics with charts and sampling context. Existing draft measurement definitions are CCU over the last five minutes, request rate per minute, and 5xx failures per minute; an error percentage must identify its denominator. These definitions are not claims of implemented collection. |
| ADM-MON-03 | Aggregated telemetry reads shall use dedicated monitoring sources/buffers without disrupting the primary transactional database. This does not select a specific cache or replica architecture. |
| ADM-MON-04 | Dashboard polling shall not perform full-table scans of transactional business tables. |
| ADM-MON-05 | The Admin shall be able to observe live changes or select time ranges to inspect resource trends. |
| ADM-MON-06 | When a metric exceeds a configured safety threshold, the dashboard shall immediately change its warning color. Thresholds, refresh intervals, and history retention remain to be specified. |
| ADM-MON-07 | Unavailable metrics shall be distinguished from zero activity or healthy operation. |

### 3.2 UC-10 and UC-22 — Monitor and Trigger Background Tasks

UC-10 precondition: the common authenticated Platform Admin conditions hold. Postcondition: task information and execution history are displayed without changing task configuration or requesting execution.

UC-22 preconditions: the common conditions hold and the target task exists. Postcondition: the request is accepted, queued for asynchronous processing, and attributed to the triggering Admin.

| ID | Requirement |
|---|---|
| ADM-CRON-01 | The system shall display registered background tasks, operational states, schedules, and latest execution results. |
| ADM-CRON-02 | The Admin shall be able to select an existing task and manually request execution without waiting for its periodic schedule. |
| ADM-CRON-03 | The system shall queue accepted manual work for asynchronous execution, record the triggering Admin, and confirm acceptance. Queued, running, and completed states shall not be conflated. |
| ADM-CRON-05 | The system shall display selected task information and execution history, including start time, end time, execution result, and recorded error information for failed executions. |
| ADM-CRON-06 | If the system cannot queue a task, it shall report triggering failure and record the error without claiming successful acceptance or execution. |
| ADM-CRON-07 | The system shall distinguish an empty task list from a selected task with no execution history and display the corresponding message. |
| ADM-CRON-08 | Before accepting a manual request, the system shall check execution conditions. If the task is running and concurrent execution is prohibited, it shall reject the request and indicate that the task is being processed. Other unmet conditions shall result in rejection with the reason displayed. |

ADM-CRON-04 (schedule editing and task pausing) is retired from this selected draft scope and is not reassigned. Viewing schedules remains in scope; editing schedules and pausing/resuming tasks are outside the supplied flows. Existing background delivery workers do not by themselves implement these Admin interfaces. Execution eligibility, concurrency enforcement, and queue integration require an approved implementation contract.

### 3.3 UC-11–12 — View Reports

Preconditions: the common conditions hold; details require an existing accessible report. Postcondition: list/details are displayed without changing report state or applying sanctions.

| ID | Requirement |
|---|---|
| ADM-REP-01 | The system shall display report summaries and permit filtering by stored report status and target type (`COMPANY`, `CANDIDATE`, `JOB`). Summaries shall include target information, complaint content, submission time, and current status. |
| ADM-REP-08 | Selecting a report shall display complaint content, permitted reporter information, reported target, evidence, current status, and an existing processing outcome when present. |
| ADM-REP-09 | No reports or no matching reports shall produce an explicit empty/no-results view. Retrieval failure shall be reported separately and permit retry. |
| ADM-REP-10 | Missing or inaccessible reports shall not be displayed. Unavailable evidence shall be identified while the remaining retrievable details remain visible. |
| ADM-REP-11 | Viewing the list or details shall not begin processing, acquire a processing lock, resolve/reject a report, or automatically apply sanctions. |

Former `ADM-REP-02–07` described review locks, resolution concurrency, sanction selection, and terminal-state updates. They are outside the user’s selected read-only scope and are not acceptance requirements for UC-11–12. Existing outcomes remain readable. This revision does not authorize removal of another approved product workflow.

### 3.4 UC-13–14 — Hide Job Posting and View History

Preconditions: hiding requires a public `PUBLISHED` posting; history requires an authorized posting context. Postconditions: successful hiding produces `SUSPENDED` and removes public visibility; history inspection changes nothing.

| ID | Requirement |
|---|---|
| ADM-JOB-01 | The Admin shall be able to hide a public `PUBLISHED` posting by supplying a mandatory reason. |
| ADM-JOB-02 | After rechecking authority and current state, the system shall set `SUSPENDED` and retain the actor, time, and reason. Existing version safeguards shall remain applicable; this draft does not remove them. |
| ADM-JOB-03 | A hidden posting shall be removed from related public-content caches. |
| ADM-JOB-04 | A hidden posting shall no longer appear in public display or search results. Search/cache mechanisms require an approved integration contract. |
| ADM-JOB-05 | A Candidate following the hidden posting’s link shall receive a suspended/unavailable indication. Missing reason or a non-public current state shall prevent hiding. |
| ADM-JOB-06 | Hiding shall record an attributable audit entry and preserve posting content/history for authorized tracing. |
| ADM-JOB-07 | The Admin shall be able to view recorded posting-history events, including time, actor, action, status, and reason where available, and return to the posting. |
| ADM-JOB-08 | History inspection shall not edit events or content. Empty history and retrieval failure shall be displayed distinctly. |

This scope does not add job closure, physical deletion, a new publication state, or access to unrelated applicant data and Company communications.

### 3.5 UC-15–17 — Company Verification

Preconditions: dossier viewing requires an existing dossier; decisions require `PENDING`. Postconditions: viewing leaves status unchanged; a successful decision records `VERIFIED` or `REJECTED` with attributable history and the applicable notification intent.

| ID | Requirement |
|---|---|
| ADM-VER-01 | The system shall provide a paginated verification queue defaulting to `PENDING`, ordered by creation time ascending, with filters `PENDING`, `VERIFIED`, `REJECTED`, and `ALL`. |
| ADM-VER-02 | The system shall display Company name, tax code, stored contact details, verification state, and existing decision/reason. For enhanced onboarding it shall also display retained submissions, address, submitter position, representation, declaration, and legal documents. Admins may download accessible evidence. Legacy dossiers shall display only actually retained data. |
| ADM-VER-03 | The Admin shall be able to approve a `PENDING` dossier. The system shall set `VERIFIED`, retain reviewer/time, increment the version, and preserve the approved decision contract. |
| ADM-VER-04 | Rejection shall require a reason of 10–1000 characters after trimming. The system shall set `REJECTED`, retain reason, reviewer/time, increment the version, and update a retained submission outcome where present. |
| ADM-VER-05 | A decision notification shall be addressed to the user who submitted the verification request. For enhanced retained submissions the existing implementation shall record a delivery intent in the decision transaction and dispatch email asynchronously after commit. Failed delivery shall not undo a committed decision. Legacy URL-only dossiers without retained attempts remain a known notification gap, not a fulfilled delivery guarantee. |
| ADM-VER-06 | The decision shall record an immutable audit entry, including actor, request context, and before/after data, in the same transaction. A failed transaction shall not leave a successful decision. |
| ADM-VER-07 | Decisions shall require the current `expectedVersion`. A stale version or competing update shall be rejected without overwriting another decision. Non-pending dossiers shall receive an invalid-state response rather than a new decision. |
| ADM-VER-08 | Empty queues shall be displayed explicitly; missing/inaccessible evidence shall not be downloaded; already-reviewed dossiers shall show their outcome without decision controls. |
| ADM-VER-09 | After a successful decision the interface shall reload the list. On error the system shall report the cause; automatic conflict reload is not claimed by these use cases or the current interface. |

Approval does not introduce a separate badge service, search-ranking boost, automatic external-registry check, or expanded communication permission. Those older reference-document claims are not established by UC-15–17. Existing Company eligibility rules continue to apply.

### 3.6 UC-18 — View Account Information

Precondition: the target exists, subject to the common authorization conditions. Postcondition: information is displayed without changing status or access rights.

| ID | Requirement |
|---|---|
| ADM-ACC-01 | The Admin shall be able to search for and select a user account or Company, and inspect permitted identifying data, current state, and ban information. |
| ADM-ACC-02 | The interface shall offer Ban or Unban when the corresponding conditions are met. |
| ADM-ACC-03 | Missing/inaccessible targets shall not be displayed. Retrieval errors shall be reported and permit retry. |
| ADM-ACC-04 | Credentials, password hashes, tokens, and unrelated private communications shall not be displayed. |

Exact search fields, private-field visibility, and pagination require a scoped contract before implementation.

### 3.7 UC-19–20 — Ban and Unban

Preconditions: banning requires an existing active target; unbanning requires a currently banned target. Postconditions: the enforcement state changes with required session invalidation and an audit record.

| ID | Requirement |
|---|---|
| ADM-BAN-01 | The Admin shall be able to ban an active user account or non-banned Company with a reason and a temporary or permanent duration. The system shall validate authority, target, state, and duration. Self-ban shall be refused. |
| ADM-BAN-02 | The system shall retain banned state, reason, and duration, and revoke related sessions. A Company ban shall also suspend public postings, revoke related recruiters’ sessions, and hide the Company from public search. |
| ADM-BAN-03 | A ban shall not be confirmed as successful unless required session revocation has succeeded. Implementation must define consistent failure/rollback behavior; this use-case revision does not select a distributed transaction mechanism. |
| ADM-BAN-04 | Access restrictions shall remain effective for the applicable ban duration. The Admin use cases do not select a Redis key format, TTL, or fallback architecture. |
| ADM-BAN-05 | The Admin shall be able to unban a currently banned target by entering a mandatory unban reason. Missing targets, no active ban, or missing reasons shall prevent success. |
| ADM-BAN-06 | Unbanning shall end the active ban, restore the applicable active/non-banned state and permitted access, and keep sessions issued before or during the ban invalid. The user shall sign in again. Failed mandatory invalidation shall not be confirmed as a successful unban. |
| ADM-BAN-07 | Ban and unban shall record attributable audit entries with the target’s before/after state. |
| ADM-BAN-08 | Restored access shall remain subject to existing role, membership, and verification conditions. Unbanning shall not grant a new role, verify a Company, or automatically republish suspended postings. |

### 3.8 Audit safeguards supporting the selected use cases

| ID | Requirement |
|---|---|
| ADM-AUD-01 | State-changing administrative actions in this scope shall create an attributable audit record. Read-only list/detail/history views do not imply mutations. |
| ADM-AUD-02 | Company verification audit records shall join the decision transaction. Other mutations require a scoped consistency contract before implementation. |
| ADM-AUD-03 | Existing append-only audit protections shall remain intact. This documentation change shall not weaken or change committed audit persistence guards. |
| ADM-AUD-05 | Audit or review responses shall not bypass the common privacy restrictions. |

Former `ADM-AUD-04` audit-log search is outside this selected inventory. Audit recording is retained; an Admin audit-search screen/API is not added by these twelve use cases.

## 4. Contracts and implementation status

### 4.1 Existing verification contracts

These are existing interfaces, not newly selected routes. Consult the approved verification specification and onboarding implementation documentation for complete payload and compatibility rules.

| Operation | Existing interface / constraint |
|---|---|
| Verification queue | `GET /api/admin/companies/verifications`; default `status=PENDING`, `page=0`, `size=20`; supported statuses include `ALL`; page is non-negative and size is 1–100. |
| Decision | `PUT /api/admin/companies/{id}/verify`; `status` is `VERIFIED` or `REJECTED`, current `expectedVersion` is required, rejection reason is 10–1000 trimmed characters. |
| Rejection convenience route | `PUT /api/admin/companies/{id}/reject`; existing rejection reason and version constraints remain applicable. |
| Retained dossier history | `GET /api/admin/companies/{id}/verification-history`; existing Platform Admin access applies. |
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

The repository does not currently provide the Admin report-list/detail, job hiding/history, account lookup/ban/unban, monitoring dashboard, or background-task management interfaces described above. Background delivery workers and existing session/authentication helpers do not establish those complete workflows.

Older versions of this Admin draft contained proposed report-resolution, account, job, metrics, task, and audit-search endpoints, SQL schemas, Redis keys, Kafka topics, and search integrations. They are not treated as implemented or approved contracts in this revision. No deployed API removal, schema migration, dependency change, or architecture selection is authorized by their removal from the active draft. Concrete contracts for unimplemented capabilities must be proposed and approved separately.

## 5. Scope exclusions and unresolved decisions

| Topic | Boundary / follow-up |
|---|---|
| Login | Remains an authentication prerequisite; excluded as a use case here. |
| Standalone job view and closure | Not included in the supplied UC 9–20 and UC-22. Contextual job viewing remains necessary for hiding/history. This does not resolve the older documents’ disagreement over Platform Admin closure rights. |
| Report adjudication | Review locks, warning/ban/takedown sanction selection, resolution/rejection, and resolution concurrency are outside UC-11–12. Do not implement them as side effects of opening details. Wider approved product obligations are not removed by this selected scope. |
| Audit-log search | Outside the selected inventory; immutable audit recording remains required. |
| Platform/Company authority | Older `rbac_matrix.md` describes role inheritance, while approved authentication separates Platform role from Company membership. Preserve the approved boundary; this edit does not amend the older matrix. |
| Verification reference | `company_verification.md` includes broader registry/domain/forensics checks, authorization-letter rules, badges, and ranking claims. They are not acceptance requirements introduced by this revision; approved onboarding/verification contracts govern implementation. |
| Monitoring | Thresholds, sampling/refresh, trend aggregation, history retention, and configured sources remain to be specified. Do not fabricate unavailable metrics. |
| Background tasks | Schedule representation, execution eligibility, queue/orchestration integration, concurrency enforcement, and operational failure handling require a contract. Schedule editing and task pausing/resuming are outside this scope. A queue acknowledgement must not claim running/completion. |
| Search/cache removal | Public hiding must hold across relevant read paths. Technology and cross-system failure behavior remain unselected. |
| Account enforcement | Exact authorization, private-field scope, session invalidation, ban-expiry handling, consistency, and failure recovery require approved implementation contracts. UC-20 does not require automatic republication. |
| Notification coverage | Enhanced attempts have delivery support; legacy decision notification remains a gap. No new global notification architecture is selected. |

These boundaries are explicit gaps, not permission to bypass the stated outcomes or change an approved requirement. The specification remains Draft until formal approval is recorded.

## 6. Acceptance and verification scenarios

| Use case | Minimum verification |
|---|---|
| UC-09 | Metrics/charts and selected time ranges display correctly; threshold breach changes warning color; unavailable sources are identified; polling does not scan primary business tables. |
| UC-10 | Task list shows operational states, schedules, and latest results; details show start/end times, results, and recorded errors; empty task list and no execution history have distinct messages; viewing changes no task configuration or execution state. |
| UC-22 | Eligible manual requests are queued and attributed to the Admin before acceptance is confirmed; acceptance differs from running/completed; prohibited concurrent runs and other unmet conditions are rejected with reasons; queue failures report failure and record the error. |
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

This is a documentation-only revision. These scenarios guide implementation verification; they are not claims that tests for unimplemented Admin features already pass.

## 7. Related documents

- [User-aligned Admin use cases](usecase.md)
- [Existing RBAC reference](rbac_matrix.md)
- [Company verification reference](company_verification.md)
- [Approved Company verification specification](../../software-requirements/rec/company-verification-spec.md)
- [Approved Authentication specification](../../software-requirements/auth/authentication-authorization-spec.md)
- [Confirmed onboarding implementation contract](../../software-requirements/rec/company-onboarding-implementation-contract.md)
- [Onboarding implementation and delivery configuration](../../backend/employer-onboarding.md)
- [Backend architecture and verification commands](../../../backend/README.md)
