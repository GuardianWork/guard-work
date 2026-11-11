# Notification Flow — Software Requirements Specification (Backend)

| Field | Value |
|---|---|
| **Specification ID** | GW-SR-NOTIF-01 |
| **Project** | GuardWork |
| **Module** | Notifications |
| **File** | `notification-flow-spec.md` |
| **Version** | 1.0.0 |
| **Date** | 2026-11-09 |
| **Last Updated** | 2026-11-09 |
| **Owner** | GuardWork thesis team |
| **Named human approver** | chiko |
| **Status** | Draft — ADR 0012 two-table contract approved 2026-11-09 |
| **Related architecture** | `docs/architecture/decisions/0012-notification-outbox-inbox-contract.md` |

---

## 1. Purpose and scope

This specification defines the durable notification pipeline that moves a business fact into a
recipient's read-side inbox:

- recording a minimal event in the same transaction as the business change;
- rendering presentation copy once when delivery occurs;
- retrying failed inbox writes with bounded exponential backoff;
- recovering undelivered events through a scheduled worker; and
- exposing recipient-scoped, paginated read state through `/api/me/notifications`.

Supported backend event types are:

- `RECRUITER_ACCEPTED`, `MEMBERSHIP_REMOVED`;
- `JOB_ASSIGNED`, `JOB_UNASSIGNED`, `JOB_NEEDS_REASSIGNMENT`, `JOB_REVISION_APPROVED`, `JOB_REVISION_REJECTED`;
- `APPLICATION_STATUS_CHANGED`, `APPLICATION_REJECTED`, `APPLICATION_WITHDRAWN`,
  `APPLICATION_INTERVIEWING`, `APPLICATION_HIRED`.

The implementation intentionally does not add push channels, email delivery, WebSockets, read
bundling, or per-recipient audience expansion beyond the recipients explicitly named by business
services.

## 2. Overall description

### 2.1 High-level flow

```
Business transaction
  -> NotificationIntentService.record*()
  -> notification_outbox (fact + queue state, same DB transaction)
  -> after-commit TransactionSynchronization
  -> NotificationDispatcher.dispatch(eventId)
  -> REQUIRES_NEW transaction
  -> NotificationTemplate.find(eventType)
  -> notification_inbox (frozen presentation snapshot)
  -> event.delivered_at = now

Failure recovery:
  MembershipNotificationWorker (every company.membership.notifications.poll-ms)
  -> NotificationDispatcher.dispatchDue()
  -> outbox rows with delivered_at IS NULL and next_attempt_at <= now
```

Current callers dispatch in one of two equivalent post-commit shapes:

- **Service-managed after-commit callback:** Job draft, job assignment, job publication, and
  application status/withdrawal register `TransactionSynchronization.afterCommit()` and call
  `NotificationDispatcher.dispatch(eventId)` when the business transaction commits.
- **Transaction-template return:** Membership removal and invitation acceptance run the business
  work in `TransactionTemplate`, then dispatch immediately after the template returns (that is,
  after commit).

Both shapes keep the outbox fact inside the business transaction; the worker remains the
recovery path for every event.

### 2.2 Current backend flow

The current implementation maps directly to the following runtime components:

1. `MembershipManagementService.remove()` records `MEMBERSHIP_REMOVED` and calls
   `dispatcher.dispatch()` after commit.
2. `RecipientInvitationService.accept(...)` records `RECRUITER_ACCEPTED` for the Company Owner
   and dispatches after commit.
3. `JobDraftService.create(...)` records `JOB_ASSIGNED` when the creating user is not Owner
   responsible.
4. `JobAssignmentService.assign()/...` records `JOB_ASSIGNED`, `JOB_UNASSIGNED`, and
   `JOB_NEEDS_REASSIGNMENT`.
5. `JobPublicationService.decide(...)` records `JOB_REVISION_APPROVED` or
   `JOB_REVISION_REJECTED` for the Owner and current Recruiters.
6. `ApplicationWorkflowService.changeStatus(...)` and `withdraw(...)` record
   `APPLICATION_*` and dispatch after commit.
7. `NotificationIntentService` is the only application writer to `notification_outbox`.
8. `NotificationDispatcher` is the only application writer to `notification_inbox`. The local
   development seed writes fixture rows directly, including outbox rows already marked
   `delivered_at` with matching inbox snapshots.
9. `MembershipNotificationWorker` polls every `company.membership.notifications.poll-ms`
   seconds and re-dispatches due rows unless the scheduler is disabled. Despite the
   membership-oriented property name, it drains every notification event type.
10. `NotificationController` exposes `GET /api/me/notifications` and
    `PATCH /api/me/notifications/{id}/read`.

The database boundary additionally freezes snapshots and read receipts through PostgreSQL
triggers (`protect_notification_outbox_context` and `protect_notification_inbox_context`).

### 2.3 Actors

| Actor | Permission |
|---|---|
| Authenticated active User | Read own inbox and mark own items read. |
| Business service transaction | Record and request delivery of notification facts. |
| Platform Staff | No special read access; inbox access remains self-only. |
| Operator | Trusted `NotificationDispatcher.replay(eventId)` recovery seam only. |

## 3. Data models

### 3.1 notification_outbox — delivery queue

| Column | Type | Required | Meaning |
|---|---|---|---|
| `event_id` | UUID | Yes | Primary key and idempotency key. |
| `recipient_user_id` | BIGINT | Yes | Target user; foreign key to `users`. |
| `event_type` | VARCHAR(50) | Yes | One of the 10 keys plus `UNKNOWN` fallback. |
| `audience` | VARCHAR(20) | Yes | `CANDIDATE` or `RECRUITER` — the role the recipient was notified as (V105). |
| `payload` | JSONB | Yes | Frozen fact-time context: `companyId`, `companyName`, optional job/application context, `jobTitle` for application events, feedback, actor. |
| `created_at` | TIMESTAMPTZ | Yes | Business fact time. |
| `attempt_count` | INTEGER | Yes | Number of failed writes. |
| `next_attempt_at` | TIMESTAMPTZ | Yes | Next delivery attempt. |
| `delivered_at` | TIMESTAMPTZ | No | Set once after inbox insert. |
| `error_category` | VARCHAR(50) | No | Last failure category. |

Indexes: `ix_notification_outbox_due (next_attempt_at) WHERE delivered_at IS NULL`.

Payload keys (camelCase; absent means null):

| Key | Type | Present | Meaning |
|---|---|---|---|
| `companyId` | number | always | Owning Company at fact time. |
| `companyName` | string | always | Frozen Company name. |
| `jobId`, `revisionId` | number | job events | Context snapshot where applicable; `revisionId` requires `jobId`. |
| `jobTitle` | string | application events | Applied-for role, copied from `application_snapshots.job_title` at intent time. |
| `applicationId` | UUID string | `APPLICATION_*` only | Required iff event is `APPLICATION_*`. |
| `candidateFeedback` | string | rejection only | Candidate-facing rejection feedback. |
| `actorUserId`, `actorName` | number / string | reserved | Frozen actor attribution. |

The payload is extensible without a migration: adding a key is an additive change to
`NotificationPayload`; readers ignore unknown keys and absent keys deserialize as null. The
database keeps only invariants shared by all events as `CHECK`s, and
`validate_notification_outbox_payload()` re-checks Company/Job/revision/Application references
at write time because the folded columns' foreign keys no longer exist.

### 3.2 notification_inbox — user read model

| Column | Type | Required | Meaning |
|---|---|---|---|
| `id` | BIGSERIAL | Yes | Scroll/order key. |
| `source_event_id` | UUID | Yes | Unique FK to `notification_outbox.event_id`. |
| `recipient_user_id` | BIGINT | Yes | Recipient. |
| `event_type` | VARCHAR(50) | Yes | Fact type. |
| `audience` | VARCHAR(20) | Yes | `CANDIDATE` or `RECRUITER` (V105); frozen copy of the outbox audience. |
| `category` | VARCHAR(50) | Yes | One of 10 UI categories. |
| `title` | VARCHAR(200) | No* | Frozen user-facing title. |
| `body` | TEXT | No* | Frozen user-facing body. |
| `action_href` | VARCHAR(1024) | No | Relative frontend destination. |
| `actor_user_id`, `actor_name` | BIGINT / VARCHAR(255) | No | Frozen actor attribution. |
| `company_id`, `company_name`, `job_id`, `revision_id`, `application_id`, `candidate_feedback`, `created_at` | mixed | Mixed | Context snapshot copy. |
| `read_at` | TIMESTAMPTZ | No | Set once by recipient. |

Indexes: `ix_notification_inbox_recipient (recipient_user_id, id DESC)`,
`ix_notification_inbox_category (category, id DESC)`, and
`ix_notification_inbox_recipient_audience (recipient_user_id, audience, id DESC)` (V105).

\* Nullable for legacy rows written before the envelope existed; the API substitutes template
fallback copy when null.

## 4. Functional requirements

### 4.1 Recording intent
- **FR-NOTIF-01:** A notification is recorded only inside an active business transaction.
- **FR-NOTIF-02:** Only the event keys supported by the intent method may be recorded:
  `record()` accepts the two membership events, `recordJob()` accepts the five job events, and
  `recordApplication()` accepts the five application events.
- **FR-NOTIF-03:** Recording returns a durable `event_id` for later dispatch.
- **FR-NOTIF-04:** Missing base context (recipient, Company) is rejected at write time by the
  outbox's required columns, payload shape checks, and the payload validation trigger. Unsupported
  event/method combinations throw `IllegalArgumentException`.
- **FR-NOTIF-04c:** Fact-time context is stored once in `notification_outbox.payload`. Adding a
  payload field requires no migration; the dispatcher maps payload keys to the inbox context
  columns and renders copy from the payload, never from a live business join.
- **FR-NOTIF-04b:** The caller supplies the `audience` (`CANDIDATE` or `RECRUITER`) the
  recipient is notified as. Membership/job events and `APPLICATION_WITHDRAWN` use
  `RECRUITER`; `APPLICATION_STATUS_CHANGED`, `APPLICATION_REJECTED`,
  `APPLICATION_INTERVIEWING`, and `APPLICATION_HIRED` use `CANDIDATE`.
  A null audience throws `IllegalArgumentException`. The dispatcher copies it to the inbox.

### 4.2 Delivery
- **FR-NOTIF-05:** Dispatch locks the outbox row before testing whether delivery succeeded.
- **FR-NOTIF-06:** No inbox row is created before dispatch; every delivery transaction is separate
  from the business transaction.
- **FR-NOTIF-07:** Presentation is rendered exactly once by `NotificationTemplate`.
- **FR-NOTIF-08:** `delivered_at` is set only after the inbox row is persisted.
- **FR-NOTIF-09:** A failed inbox write updates `attempt_count`, delays `next_attempt_at`,
  and stores `error_category=INBOX_WRITE_FAILED`.
- **FR-NOTIF-10:** Retries use at least 30 seconds of delay and no more than 30 minutes.
- **FR-NOTIF-11:** Worker dispatch uses a batch of 100 rows ordered by attempt schedule.
- **FR-NOTIF-12:** `replay` is an operator-only recovery operation and does not alter
  already-delivered rows.

### 4.3 Read model
- **FR-NOTIF-13:** `GET /api/me/notifications` returns only the authenticated active User's rows.
- **FR-NOTIF-14:** Results are sorted by `id DESC`.
- **FR-NOTIF-15:** Pagination is bounded to 1–20 rows (default 5); invalid page/size values
  normalize to the nearest valid bound rather than failing the request.
- **FR-NOTIF-16:** `PATCH .../{id}/read` updates `read_at` only if it is null and only for
  the recipient. Repeating the call on an already-read row returns 200 with the existing
  timestamp; a missing or foreign ID returns `NOTIFICATION_NOT_FOUND`.
- **FR-NOTIF-17:** Null snapshot fields fall back in responses: blank category resolves from the
  event template, blank title resolves to the raw event type, null body becomes `""`, and
  blank `action_href` becomes a null `action`. Actor is null unless both actor ID and name exist.
- **FR-NOTIF-18 (P0):** List accepts optional filters `read` (`true`/`false`), `category`
  (wire value, case-insensitive), and `eventType` (wire value, case-insensitive). Filters
  combine with `AND` and are applied in PostgreSQL before paging. Unknown category/event
  values and negative `sinceId` return `40000 INVALID_INPUT`; no filter returns the unfiltered inbox.
- **FR-NOTIF-19 (P0/P1):** List accepts optional `sinceId` (`id > sinceId`, `id` DESC order kept).
  It lets a client poll for items newer than the last one it saw without refetching page 0.
- **FR-NOTIF-20 (P0):** `GET /api/me/notifications/unread-count` returns `{ unreadCount }`
  for the authenticated active User. Clients must use it for badge/tab counts instead of
  deriving a count from a single page.
- **FR-NOTIF-21 (P0):** `PATCH /api/me/notifications/read` marks every currently-unread row
  of the recipient read in one transaction and returns `{ updatedCount, readAt }`, where
  `updatedCount` is the number of rows changed and `readAt` is the shared timestamp of the
  batch. With no unread rows it returns `updatedCount: 0` and the current time.
- **FR-NOTIF-22 (P1):** `GET /api/me/notifications/{id}` returns one recipient-owned item
  with the same `NotificationResponse` shape as the list. Missing or foreign IDs return
  `40406 NOTIFICATION_NOT_FOUND`. Clients use it for detail views and deep-link checks for
  `action.href` targets.
- **FR-NOTIF-23 (audience):** Every read endpoint accepts optional `audience`
  (`CANDIDATE`/`RECRUITER`, case-insensitive). When provided, list/count/mark-all/detail/
  single-read are scoped to that audience for the recipient; detail and single-read return
  `40406` for cross-audience IDs. Omitting it returns the unfiltered (all-audience) inbox
  for backwards compatibility. Unknown values return `40000 INVALID_INPUT`.
- **FR-NOTIF-24 (audience presentation):** `NotificationResponse` includes `audience`
  (wire value) so clients can confirm which portal an item belongs to. Candidate portals
  request `CANDIDATE`; recruiter portals request `RECRUITER`.

## 5. Business rules

- **BR-NOTIF-01:** Outbox context never changes after insert.
- **BR-NOTIF-02:** Delivered dispatch metadata never changes after `delivered_at`.
- **BR-NOTIF-03:** Inbox context and presentation never change after insert.
- **BR-NOTIF-04:** `read_at` is immutable once set.
- **BR-NOTIF-05:** `companyName` is frozen in the outbox payload at intent time. Application
  events also freeze `jobTitle` from the application snapshot. Actor fields are frozen when
  populated; current callers leave both actor keys absent (see open gaps).
- **BR-NOTIF-06:** Application events require `applicationId`; non-application events cannot
  carry one (enforced by `ck_application_outbox_context` / `ck_application_inbox_context`).
- **BR-NOTIF-07:** One delivered outbox row produces exactly one inbox row.
- **BR-NOTIF-08:** Template changes never rewrite historical notifications.
- **BR-NOTIF-09:** A rollback of the business transaction also rolls back the notification intent.
- **BR-NOTIF-10:** Failure to render or write does not roll back a committed business transaction.
- **BR-NOTIF-11:** `audience` is set by the intent caller at record time and never changes;
  it is not derived from the event type at read time (`APPLICATION_WITHDRAWN` is
  recruiter-facing while the other `APPLICATION_*` events are candidate-facing, including
  `APPLICATION_INTERVIEWING` and `APPLICATION_HIRED`). Backfilled
  V105 rows use the same rule. `CONTEXT` triggers freeze `audience` and the outbox `payload`
  like the rest of the context columns.

## 6. API specification

### 6.1 List notifications

- **Endpoint:** `GET /api/me/notifications`
- **Auth:** Authenticated active User.
- **Query:** `page` (default `0`), `size` (default `5`, max `20`).
- **Response:** `ApiResponse<PageResponse<NotificationResponse>>`.
- Important fields include `id` (string), `type`, `category`, `title`, `body`, `createdAt`,
  `read`, `readAt`, `action`, `actor`, and `references`.

Example item:

```json
{
  "id": "42",
  "type": "APPLICATION_REJECTED",
  "category": "application",
  "title": "Hồ sơ ứng tuyển đã bị từ chối",
  "body": "Hồ sơ ứng tuyển của bạn tại Công ty A đã bị từ chối. Kinh nghiệm chưa phù hợp.",
  "createdAt": "2026-11-09T10:00:00Z",
  "read": false,
  "readAt": null,
  "action": { "href": "/applications/9b1c…" },
  "actor": null,
  "references": {
    "companyId": 7,
    "companyName": "Công ty A",
    "jobId": 15,
    "revisionId": 22,
    "applicationId": "9b1c…",
    "candidateFeedback": "Kinh nghiệm chưa phù hợp."
  }
}
```

### 6.2 Mark read

- **Endpoint:** `PATCH /api/me/notifications/{id}/read`
- **Auth:** Authenticated active User, matching recipient only.
- **Locking:** The service uses a pessimistic write lock on the recipient-scoped inbox row.
- **Response:** HTTP 200 with `{ id, readAt }`; an already-read row returns its original timestamp.
- **Errors:** `NOTIFICATION_NOT_FOUND` (`40406`) for missing/foreign IDs.

### 6.3 List with filters and polling

- **Endpoint:** `GET /api/me/notifications?page&size&read&category&eventType&sinceId&audience`
- **Auth:** Authenticated active User.
- **Query:**
  - `page` (default `0`), `size` (default `5`, max `20`) — unchanged.
  - `read` — `true` returns only rows with `read_at` set; `false` returns only rows with
    `read_at` null; omitted returns both.
  - `category` — one of the 10 wire values (`job`, `application`, `company`, `account`,
    `verification`, `announcement`, `community`, `reward`, `promotion`, `system`).
  - `eventType` — one of the persisted `event_type` values (e.g. `APPLICATION_REJECTED`),
    including `UNKNOWN` for legacy rows.
  - `sinceId` — return only rows with `id > sinceId` (newer than the last id the client saw),
    still sorted `id DESC`. Negative values are rejected.
  - `audience` — `CANDIDATE` or `RECRUITER` (case-insensitive); omitted returns both.
    Candidate portals pass `CANDIDATE`, recruiter portals `RECRUITER`.
- **Response:** Same `ApiResponse<PageResponse<NotificationResponse>>` as §6.1. Filters
  run in PostgreSQL before paging, so `totalElements` reflects the filtered set.
- **Errors:** `40000 INVALID_INPUT` for unknown `category`/`eventType`/`audience` or negative `sinceId`.
- **Compatibility:** Omitting all new parameters behaves exactly as before.

### 6.4 Unread count

- **Endpoint:** `GET /api/me/notifications/unread-count?audience=`
- **Auth:** Authenticated active User.
- **Response:** HTTP 200 with `{ unreadCount }` (recipient rows where `read_at IS NULL`,
  optionally scoped to `audience`; omitted counts all audiences).
- **Use:** Bell badge and tab counts. Derive counts from this endpoint, not from a single list page.

### 6.5 Mark all read

- **Endpoint:** `PATCH /api/me/notifications/read?audience=`
- **Auth:** Authenticated active User.
- **Behavior:** One transaction updates every unread row of the recipient with a single
  `readAt` timestamp. Atomic: all unread rows become read or the request fails.
  With `audience`, only that audience's rows are marked.
- **Response:** HTTP 200 with `{ updatedCount, readAt }`. With nothing unread it returns
  `updatedCount: 0` and the current time.
- **Compatibility:** Replaces the client N-request fan-out (`Promise.all` per item);
  existing single-item `PATCH .../{id}/read` is unchanged.

### 6.6 Get one notification

- **Endpoint:** `GET /api/me/notifications/{id}?audience=`
- **Auth:** Authenticated active User, matching recipient only.
- **Response:** HTTP 200 with the single `NotificationResponse` (§6.1 shape, including `audience`).
- **Errors:** `NOTIFICATION_NOT_FOUND` (`40406`) for missing/foreign IDs, and for IDs whose
  `audience` does not match a provided `audience` parameter.

## 7. Error catalogue

| Condition | Code / status | Message |
|---|---|---|
| Unsupported event type | internal `IllegalArgumentException` | `Unsupported … notification` |
| Missing or foreign notification | `40406 NOTIFICATION_NOT_FOUND` | `Notification not found.` |
| Invalid `category` / `eventType` / `audience` / negative `sinceId` | `40000 INVALID_INPUT` | `Invalid input. Please check your data` |
| Missing application context | DB `CHECK` violation; internal error | `ck_application_*_context` |
| Unknown/legacy event type | `UNKNOWN` is accepted by the DB `CHECK`; other values cannot be stored | n/a |
| DB write failure during delivery | no API response; retry state and warning | `INBOX_WRITE_FAILED` |

## 8. Non-functional requirements

- **Durability:** Notification facts commit with their business facts.
- **At-least-once delivery:** Dispatcher retries failed inbox writes; duplicates are prevented.
- **Ordering:** Results are ordered `id DESC`; dispatch ordering is by attempt schedule.
- **Concurrency:** Outbox rows are locked during delivery; concurrent dispatchers serialize.
- **Privacy:** Users never see another user's inbox rows; IDs map to recipients.
- **Performance:** Delivery does not join business tables because presentation is copied.
- **Auditability:** Each outbox row preserves the event and its retry state.

## 9. Verification

- `MembershipNotificationWorkerHttpTest` proves scheduled delivery.
- `RecipientInvitationHttpTest`, `JobPublicationHttpTest`, and `JobDraftHttpTest` prove
  dispatch after commit and inbox rendering.
- `NotificationInboxHttpTest` proves filtered list (`read`/`category`/`eventType`/`sinceId`),
  `unread-count`, `mark-all-read`, single-item detail (P0+P1 read endpoints), and
  audience scoping (`CANDIDATE` vs `RECRUITER` isolation + backwards-compatible unscoped calls).
- `JobMigrationCompatibilityTest` and `MigrationV8Test` prove migration history.
- Mutating outbox context in SQL should raise SQLSTATE `23514`.

## 10. Open gaps

1. Actor snapshot fields are frozen at the DB boundary but are not yet populated by intent callers.
2. Status-only changes to Application statuses can dispatch after commit, but actor attribution is pending.
3. No external channels (email/push) are implemented.
