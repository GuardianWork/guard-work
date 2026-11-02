# [GW-SR-ADM-03] Personal Staff Activity History

| Field | Value |
|---|---|
| Specification ID | GW-SR-ADM-03 |
| Use case | UC-22 — View Own Activity History |
| Vietnamese title | Xem lịch sử hoạt động của bản thân |
| Version | 0.1.0 |
| Status | Draft — product scope confirmed; technical contract awaiting review |
| Owner | GuardWork thesis team |
| Human approver | Formal named approval pending; requesting human confirmed the design interview on 2026-10-30 |
| Last updated | 2026-10-30 |
| Sources | Session decisions Q1–Q11; [Staff use cases](../../feat/staff/usecase.md); [API proposal](personal-activity-history-implementation-contract.md) |

## 1. Purpose and scope

Let an authenticated Platform Staff member inspect and find successful business
changes they personally performed. This is a personal reconciliation view, not a
cross-Staff investigation console or a complete record of working sessions.

The requesting human confirmed the scope below after the design interview and
authorized drafting this specification and its API contract. This is not approval
to implement the proposed technical contract, change a schema, or commit/push.
The remaining Staff Draft requirements are not promoted to Approved.

| Included group | Included current operations |
|---|---|
| Account management | Ban/unban a user account or Company |
| Job Posting management | Approve/reject a Job Posting revision |
| Company verification | Approve/reject a Company verification dossier |

Other data-changing operations in those three groups may be integrated when their
own business requirements are implemented and an explicit history mapping is
reviewed. This use case does not implement hiding/restoring postings or another
missing Staff capability. Report adjudication, membership administration,
operational ownership assignment, login/logout, reads, failed attempts, background
expiry, session grouping, export, and cross-account activity search are excluded.

## 2. UC-22 — View Own Activity History

- **Actor:** An authenticated, currently active Platform Staff member.
- **Trigger:** The Staff opens their personal activity-history page.
- **Preconditions:** Existing Staff authentication, session-invalidation, role,
  and portal rules hold. Company membership grants no Platform authority.
- **Postconditions:** Only the caller's matching activity is displayed. The read
  does not change business data, create a successful-action audit entry, or change
  the underlying history. Ordinary authentication enforcement remains applicable.

### Main flow

1. The Staff opens the personal history page.
2. The system identifies the actor from trusted authentication context.
3. The system selects the caller's in-scope committed audit records for the most
   recent 30 Vietnam calendar dates, including today.
4. The system displays the first page of 20 records, newest first.
5. The Staff may filter by date range, business group, action, and an exact target
   code within a selected group and target type, or move between result pages.
6. The system applies every filter together with the mandatory own-actor scope
   and displays matching records and pagination information.

### Alternative flows

- **Authentication/authority failure:** Deny the request under the existing
  authentication and Staff role rules; return no history.
- **No matching records:** Show an empty state and allow filter changes.
- **Invalid filters:** Explain which input needs correction; do not broaden the
  query or silently ignore a filter.
- **Retrieval failure:** Show a retryable failure without displaying another
  Staff member's data or presenting the failure as an empty result.
- **Incomplete historical data:** Show available persisted facts and label each
  unavailable value as “Không có dữ liệu”; do not infer historical state from
  current entity state or fabricate an old event.
- **Target unavailable:** Keep the retained activity record visible within the
  actor's history. Do not look up or grant current target access to render it.

## 3. Functional requirements

| ID | Requirement |
|---|---|
| ADM-ACT-01 | Every query shall require current Platform Staff authentication and constrain the actor to the authenticated user on the server. No client-supplied actor ID, role, or ownership assertion may select another history. |
| ADM-ACT-02 | The view shall include only committed data-changing operations in the three selected groups. Failed or rolled-back attempts, navigation, and authentication events shall not appear as successful business changes. |
| ADM-ACT-03 | Each record shall display occurrence time, action, target type/code, recorded before/after business state, and a business reason where applicable and recorded. |
| ADM-ACT-04 | Responses shall use explicit field allowlists; raw audit JSON, credentials, tokens, IP addresses, user agents, unrelated personal information, documents, messages, and downloadable evidence shall not be exposed. |
| ADM-ACT-05 | The Staff shall be able to filter by an inclusive calendar-date range, business group, action, and an exact target code within an identified group/type. All filters shall intersect the own-actor scope. |
| ADM-ACT-06 | The default range shall be the most recent 30 calendar dates including today in Asia/Ho_Chi_Minh. Display and date-input interpretation shall use Vietnam time. Older dates shall remain queryable where retained. |
| ADM-ACT-07 | Results shall use pages of 20 records ordered by occurrence time descending, then a stable unique event ID descending. |
| ADM-ACT-08 | The view shall include previously stored in-scope audit records belonging to the caller. Missing facts shall be explicitly unavailable; current state shall not be substituted for historical state. |
| ADM-ACT-09 | The page shall permit viewing, searching/filtering, and pagination only. It shall offer no edit, delete, hide, export, or target-navigation actions and no corresponding new write/export endpoint. |
| ADM-ACT-10 | Existing append-only audit protections and system retention policy shall remain unchanged. Staff shall not control retention or remove their own events. |
| ADM-ACT-11 | Restricting this personal aggregation to its owner shall not remove reviewer attribution or authorized history visibility in existing Company/Job Posting workflows. |
| ADM-ACT-12 | The page shall provide loading, empty, invalid-filter, session/permission-denial, and retry states. Retrieval errors shall not be represented as a successful empty result. |
| ADM-ACT-13 | For new included mutations after feature rollout, the writer shall record the necessary safe before/after state and applicable submitted reason in the same transaction as the mutation. Mandatory audit failure shall prevent a reported successful change. |

## 4. History and privacy interpretation

The visible time and state describe the committed action, not the target's present
state. A later reversal produces another event; it does not rewrite the earlier
record. Reading history does not itself start or end a ban or moderation workflow.

The personal page does not hide evidence of an actor's decisions from existing
authorized dossier/revision history. It introduces no supervisor or super-admin
exception for accessing another Staff member's personal aggregation.

Do not require additional names/emails in new audit snapshots for this feature.
The display identifies a target by type and code. Reasons are displayed as plain
text within existing authorization and privacy constraints; missing reasons are
null, and backend fallback prose is not presented as if the Staff entered it.

Historical job-moderation audit rows identify a revision, not directly a posting.
The technical contract distinguishes the posting code from the revision code.
An immutable identity relationship may resolve a posting ID; current mutable
business state may never reconstruct a missing before/after value.

## 5. Existing baseline and gaps

`audit_logs` already stores actor, action, target, before/after payloads, reason,
request metadata, and time. It has an actor/time index and database triggers
prohibiting UPDATE, DELETE, and TRUNCATE. An internal unpaginated actor query exists,
but no general personal-history HTTP endpoint currently exists.

Account ban/unban records contain enforcement snapshots. Company verification
records contain wider Company snapshots that must be projected through an allowlist.
Current job-revision moderation records contain a recorded outcome but no complete
before snapshot. Old records must show that omission honestly; new moderation
writers need safe before/after enrichment for ADM-ACT-13. No historical rows may
be rewritten or backfilled with invented facts.

The proposed implementation reuses audit storage and scoped existing response
conventions. No schema, dependency, retention, or global authorization change is
selected by this specification. The API proposal requires human review before
implementation. The general cross-Staff audit-search exclusion remains in force;
UC-22 is a narrow addition for the caller's own history.

## 6. Acceptance and verification

| Scenario | Required outcome |
|---|---|
| Two Staff members perform actions | Each personal query returns only that authenticated actor's in-scope events, across all filters and pages. |
| Caller sends another actor ID | The API rejects unsupported actor selection and returns no other actor's data. |
| Anonymous, Company Admin, inactive Staff, or revoked credential | Existing 401/403 rules apply; no history leaks. |
| Committed ban/unban, verification decision, or revision decision | The appropriate actor sees one activity per business action, with safe recorded facts. |
| Mutation rollback or failed attempt | No successful activity is listed. |
| New action after rollout | Its safe before/after values and applicable entered reason are captured atomically. |
| Old job decision with no before snapshot | Recorded outcome remains visible; missing before state is shown as unavailable. |
| Old Company audit includes private fields | Response excludes those fields regardless of their presence in JSON. |
| Vietnam midnight boundary | Inclusive date filters match local calendar dates; default covers exactly 30 dates. |
| Exact target code, including Company ban versus verification | Group/type filters distinguish business operations and target namespaces. |
| Multiple events share a timestamp | Event ID provides deterministic order; records from other Staff never enter the result. |
| Target has changed or is unavailable | Retained event state remains historical; no current target data/access is required. |
| Empty result, validation failure, or database outage | Distinct empty/error/retry states are available. |
| Restricted page actions | No mutation, export, raw-payload viewer, or target navigation is offered. |

Before implementation, test the selected API and projection mappings against the
existing PostgreSQL-backed test setup. Runtime tests are not claimed by this
Draft documentation change. Frontend implementation must follow its own repository
conventions; this repository currently supplies backend code only.
