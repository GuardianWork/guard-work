# [GW-SR-ADM-03] Personal Activity History — API Proposal

| Field | Value |
|---|---|
| Contract ID | GW-SR-ADM-03-API |
| Parent specification | [Personal Staff Activity History](personal-activity-history-spec.md) |
| Version | 0.1.0 |
| Status | Draft — proposed; no endpoint implemented by this document |
| Owner | GuardWork thesis team |
| Human approver | Formal named approval pending; product decisions confirmed by requesting human on 2026-10-30 |
| Last updated | 2026-10-30 |

## 1. Boundary and authority

Propose a single read endpoint:

```http
GET /api/staff/me/activities
Authorization: Bearer <accessToken>
```

Reuse existing Staff proxy/portal enforcement, persisted active Staff role checks,
and session-version invalidation. The Staff proxy overwrites `X-Guardian-Portal`
with `staff`; follow the existing compatibility behavior for omitted headers.
Company `OWNER`, `ADMIN`, or `RECRUITER` is insufficient.

Resolve actor ID only from authenticated server context. Every data and count query
must contain `audit_logs.admin_id = authenticatedUserId` as an AND constraint.
Filter options, counts, and error messages must not reveal another actor's records.
Reject unsupported query parameters, including `staffId`, `actorId`, and `userId`;
there is no selectable owner, per-Staff route, or general audit-detail endpoint.

No POST/PUT/PATCH/DELETE, export route, attachment, per-row deep link, raw-payload
endpoint, or persisted read-marker is introduced. The existing audit writer remains
part of the underlying mutation, not an operation initiated by this history screen.

## 2. Query parameters

All filters are combined with AND. Omitted category/action means all currently
mapped in-scope values. Empty optional filter strings mean omitted; invalid
nonempty values are rejected. Duplicate query parameters are rejected.

| Parameter | Proposed rule |
|---|---|
| `fromDate` | Optional strict ISO calendar date `YYYY-MM-DD` |
| `toDate` | Optional strict ISO calendar date `YYYY-MM-DD` |
| `category` | `ACCOUNT_MANAGEMENT`, `JOB_MODERATION`, or `COMPANY_VERIFICATION` |
| `action` | One action from the table below |
| `targetType` | `USER`, `COMPANY`, or `JOB_POSTING` |
| `targetId` | Exact positive decimal Long-range ID; no substring, wildcard, or name/email search |
| `page` | Integer >= 0, default 0 |
| `size` | Fixed at 20; may be omitted or explicitly equal 20 |

Supply both dates or neither. With neither, evaluate `today` once per request in
`Asia/Ho_Chi_Minh`, set `toDate=today`, and `fromDate=today.minusDays(29)`. With dates,
require `fromDate<=toDate`; there is no feature-specific retention cutoff or arbitrary
maximum range. Old dates query retained data. A future interval is valid and may
return no matches. Reject dates that cannot be converted safely into query instants.

Convert local dates into a half-open interval:

```text
created_at >= fromDate.atStartOfDay(Asia/Ho_Chi_Minh).toInstant()
created_at <  toDate.plusDays(1).atStartOfDay(Asia/Ho_Chi_Minh).toInstant()
```

For example, `fromDate=2026-10-01&toDate=2026-10-30` covers
`[2026-09-30T17:00:00Z, 2026-10-30T17:00:00Z)`.

Require both `category` and `targetType` when `targetId` is present, so account
codes cannot silently mix USER and COMPANY namespaces. A type filter without an ID
is allowed. An incompatible category/action/type combination is invalid rather
than silently ignored. If category is omitted, a valid action or target type
narrows the relevant categories normally.

### Action mapping

| API action | Category | Target type | Existing source action / condition |
|---|---|---|---|
| `BAN_ACCOUNT` | `ACCOUNT_MANAGEMENT` | `USER` or `COMPANY` | `ACCOUNT_BANNED` |
| `UNBAN_ACCOUNT` | `ACCOUNT_MANAGEMENT` | `USER` or `COMPANY` | `ACCOUNT_UNBANNED` |
| `APPROVE_JOB_REVISION` | `JOB_MODERATION` | `JOB_POSTING` | `JOB_REVISION_APPROVED`, source type `JOB_REVISION` |
| `REJECT_JOB_REVISION` | `JOB_MODERATION` | `JOB_POSTING` | `JOB_REVISION_REJECTED`, source type `JOB_REVISION` |
| `APPROVE_COMPANY` | `COMPANY_VERIFICATION` | `COMPANY` | `COMPANY_VERIFICATION`, recorded after verificationStatus `VERIFIED` |
| `REJECT_COMPANY` | `COMPANY_VERIFICATION` | `COMPANY` | `COMPANY_VERIFICATION`, recorded after verificationStatus `REJECTED` |
| `REVIEW_COMPANY` | `COMPANY_VERIFICATION` | `COMPANY` | Legacy `COMPANY_VERIFICATION` with missing/unrecognized recorded outcome; result remains unavailable |

Do not classify a Company ban as Company verification just because its target type
is COMPANY. Do not include unrelated ownership, membership, system, or recruiter
operations based on a broad substring match. Unregistered source action/type pairs
remain outside the mapped result; adding an operation requires an explicit mapping.
A legacy verification row lacking its recorded outcome uses `REVIEW_COMPANY` with
unavailable result data. Do not guess approval/rejection from current Company
state or arbitrary reason text, or drop the known in-scope event merely because
a field is missing. Never edit old audit rows to fix it. This fallback is for
legacy reads; new decision writers must store a recognized outcome.

For moderation, the exact target code is the Job Posting ID, not the revision ID.
Resolve it through the persisted immutable revision-to-posting identity relation;
return the recorded revision code separately. This identity lookup does not grant
current posting access or read mutable posting content/state. If the relationship
is unavailable, retain the event in unfiltered history with `targetId=null` and
its known `revisionId`; it cannot match an exact posting-ID filter. Do not fabricate
a posting code or treat a revision code as one.

Example filtered request:

```http
GET /api/staff/me/activities?fromDate=2026-10-01&toDate=2026-10-30&category=ACCOUNT_MANAGEMENT&action=BAN_ACCOUNT&targetType=USER&targetId=123&page=0
```

## 3. Response allowlist

Reuse `ApiResponse<PageResponse<ActivityItem>>`. Sort by `createdAt DESC, id DESC`
with server-controlled order. Offset pagination follows the existing scoped page
convention; concurrent new events can shift pages, and this proposal does not
claim snapshot pagination. The client resets page to zero for new filter input; the stateless server honors
the validated page parameter. No arbitrary sort or client-supplied owner is accepted.

`ActivityItem` contains exactly:

| Field | Type | Meaning |
|---|---|---|
| `id` | Long | Source audit event ID; not a drill-down permission |
| `occurredAt` | ISO-8601 UTC instant | Stored event time; UI renders Vietnam local time |
| `category` | Enum | One of the three categories above |
| `action` | Enum | One of the mapped actions, including the legacy review fallback |
| `targetType` | Enum | USER, COMPANY, or JOB_POSTING |
| `targetId` | Decimal string or null | Historical target code; null only when an old posting identity is unavailable |
| `revisionId` | Decimal string or null | Recorded moderation revision code, otherwise null |
| `changes` | Array of StateChange | Safe recorded before/after state; see below |
| `reason` | String or null | Recorded business reason where applicable; not raw generated audit prose |

`StateChange` contains `field`, `before`, and `after`. Values are scalar enum
strings, booleans, or null. A null value means unavailable recorded data, not an
inferred state or a claim that a target did not exist. Never return nested JSON
payloads as a shortcut. Each mapped row has one relevant state-change entry.

| Category/target | Allowed change field | Recorded source |
|---|---|---|
| Account management / USER | `status` | Before/after enforcement payload `status` |
| Account management / COMPANY | `isBanned` | Before/after enforcement payload `isBanned` |
| Company verification | `verificationStatus` | Before/after `verificationStatus` |
| Job moderation | `reviewState` | Safe recorded revision review state; legacy after `outcome` may provide the recorded after value |

A job's review state is not its publication lifecycle. Do not label `APPROVED` as
`PUBLISHED`, infer an old `PENDING_REVIEW` before value from today's code, or expose
revision content. New moderation writes must record both safe review-state values;
legacy missing values remain null. Do not substitute current state from `users`,
`companies`, `job_postings`, or a mutable revision row.

Only recognized state values for the mapped dimension may pass projection.
A malformed, unknown, or absent field becomes null rather than leaking arbitrary
payload contents or failing the entire list. No actor field is needed because all
rows already belong to the authenticated caller.

For ban/unban, use the recorded submitted reason. For rejection, use the recorded
rejection reason, including the documented snapshot field when needed by a legacy
Company row. Approval has no required entered reason in the selected use cases;
return null rather than the generated `Approved by staff ...` audit fallback.
Render reasons as plain text, never HTML. This is not a free-form audit-JSON viewer.

Example HTTP 200 response (technical English message, localized by the client):

```json
{
  "status": 200,
  "message": "Activity history retrieved successfully",
  "data": {
    "content": [
      {
        "id": 501,
        "occurredAt": "2026-10-30T02:00:00Z",
        "category": "ACCOUNT_MANAGEMENT",
        "action": "BAN_ACCOUNT",
        "targetType": "USER",
        "targetId": "123",
        "revisionId": null,
        "changes": [
          { "field": "status", "before": "ACTIVE", "after": "BANNED" }
        ],
        "reason": "Recorded violation"
      }
    ],
    "pageNumber": 0,
    "pageSize": 20,
    "totalElements": 1,
    "totalPages": 1,
    "first": true,
    "last": true
  }
}
```

No matches or an out-of-range valid page returns HTTP 200 with an empty `content`
and the correct page metadata. Do not use 404 for a missing target filter: a
history search is not a current-resource existence check.

## 4. Errors and response privacy

Use the existing scoped `{ status, message, data }` envelope. The following HTTP
semantics are proposed; feature-specific numeric identifiers must be allocated
and reviewed before implementation rather than claimed as existing codes.

| HTTP | Condition | Client behavior |
|---|---|---|
| 400 | Invalid date/page/enum/ID, incompatible filters, duplicate/unsupported query parameters, or a size other than 20 | Retain filter input and show localized validation feedback |
| 401 | Missing, expired, revoked, or inactive-account credential under current auth rules | Follow existing sign-in/session recovery |
| 403 | Current caller lacks Platform Staff authority or violates existing portal rules | Show access denied; return no activity data |
| 503 | History storage unavailable | Show retry; do not turn it into an empty result |
| 500 | Unexpected internal failure | Show retrieval failure without exposing payloads or stack traces |

Authentication/authorization must run before a query can expose results or counts.
Do not reveal whether another Staff ID or another actor's event exists. Apply
existing sanitized logging conventions when handling operational failures.

## 5. Persistence and consistency proposal

Use the existing PostgreSQL `audit_logs` as the canonical activity source. Reuse
`(admin_id, created_at DESC)` indexing and existing append-only guards initially.
Do not add a second activity table or duplicate job events from both `audit_logs`
and `job_audit_events`; one successful business action appears once in this view.
Do not migrate/backfill historic payloads or invent missing old records.

The service projects allowlisted fields from stored snapshots. Filter and paginate
in PostgreSQL rather than load an unbounded actor history into memory. A read
transaction has no business-state writes and does not change audit retention.
State filters/classification must inspect recorded JSON values safely, including
legacy malformed/missing fields. Do not add search infrastructure or a database
index without evidence and the required approval if implementation later needs it.

The current moderation writer needs future-record enrichment of its existing
before/after JSON payloads to satisfy full before/after display. This is a proposed
writer adjustment inside the original mutation transaction, not a schema change
or historical backfill. Preserve existing consumers and verification payload
behavior; use projection to hide wider existing fields.

## 6. Client behavior

Use Vietnamese labels for business groups, actions, states, and the page title
“Lịch sử hoạt động của tôi”. Use `vi-VN` with `Asia/Ho_Chi_Minh` for display.
A missing historical value renders “Không có dữ liệu”. Keep technical enum/error
values out of product copy.

On filter changes, validate locally, reset to page zero, issue a fresh GET, and
ignore stale earlier responses. On transport/server failure keep inputs and show
an explicit retry state. Render loading, empty results, and failures distinctly.
Cards/rows have no edit/delete/hide/export/menu or target-navigation controls;
filtering and pagination are the only interactions beyond reading.

This contract does not select a frontend framework or scaffolding. No UI runtime,
endpoint, migration, dependency, global security policy, notification, or retention
change is delivered by this documentation-only draft.

## 7. Required implementation checks

- Verify mandatory actor scoping for both results and totals under every filter,
  pagination, parameter-tampering, and signed/current role/session combination.
- Verify all six concrete action mappings and the legacy review fallback, Company enforcement/verification separation, and one
  activity per successful action without duplicate job event sources.
- Verify failed/rolled-back actions are absent and new writers capture safe state
  atomically; preserve missing historical fields without rewriting stored data.
- Verify state/reason allowlists against wide Company snapshots, malformed legacy
  data, and HTML-like reasons, without leaking documents, IP, agent, or raw JSON.
- Verify Vietnam midnight boundaries, 30-date default, leap days, same-time ID
  ordering, paired/invalid dates, incompatible filters, and fixed-size pagination.
- Verify posting-ID search and revision-ID display are not confused; unavailable
  identity stays visible in unfiltered history without invented target data.
- Verify empty results, unavailable storage, retries, and prohibited UI actions.

Implementation remains pending review of this concrete proposal and the required
branch approval under AGENTS.md. No runtime test result is claimed by this draft.
