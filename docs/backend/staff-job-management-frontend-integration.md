# GW-93 frontend integration: hide jobs and inspect posting history

Backend scope: UC-13 (hide a public Job Posting) and UC-14 (view posting events), under the [approved contract](../software-requirements/adm/staff-job-management-contract.md). These APIs are implemented on `feat/GW-93-jd-management`. No frontend code is included in this checkout.

## Authentication and existing job context

Use the existing Staff login/session flow and send `Authorization: Bearer <accessToken>` on both new endpoints. The Staff proxy overwrites `X-Guardian-Portal: staff`; use a token issued for that portal. The current persisted User must be active and have Platform role `STAFF`. Company `OWNER`, `ADMIN` and `RECRUITER` alone cannot use these APIs. Do not send actor IDs or `X-Staff-*` headers as proof of authority.

Keep the existing `{ status, message, data }` response handling. HTTP status is authoritative; do not infer it from the numeric prefix inside `message` (`40022` is an HTTP 409 conflict). Backend messages are English technical messages; render Vietnamese UI text.

Existing navigation can start from:

| Endpoint | Integration use |
|---|---|
| `GET /api/staff/job-revisions?status=ALL&page=0&size=20` | Existing submitted-revision queue. Each record has `jobId`, `id` (revision ID), and current `jobVersion`. The default status is `PENDING_REVIEW`, so use an appropriate filter to reach approved jobs. |
| `GET /api/staff/job-revisions/{revisionId}` | Reload submitted revision content and current `jobVersion` before a hide. Its `state` is the revision state, not the Job Posting publication status. |
| `GET /api/staff/jobs/{jobId}/revisions?page=0&size=20` | Existing submitted-revision history, ordered by revision number ascending. Each record includes current `jobVersion`; editable drafts are excluded. |
| `GET /api/jobs/{jobId}` | Public approved-content view: `publicationStatus`, `approvedDeadline`, `applicationsAvailable`. There is no version token here. Suspended jobs return a content-free 404. |

There is no new Staff job-list/detail endpoint in GW-93. The Staff queue is a revision queue and can contain several revisions of the same job; group by `jobId` if the UI needs job-oriented rows. Do not use a revision's `revisionVersion`, an event ID, or a historical event as `expectedJobVersion`. There is no complete job-status field in the existing Staff revision response. For the hide control, check the public detail response for `publicationStatus === "PUBLISHED"` and `applicationsAvailable === true`, then let the hide endpoint recheck authority, version, Company state and deadline. A 404 public response means unavailable; it does not identify the specific cause of unavailability. Staff can still inspect submitted content and recorded events through authenticated Staff endpoints.

## UC-13: Hide Job Posting

`PUT /api/staff/jobs/{jobId}/hide`

```json
{
  "expectedJobVersion": 7,
  "reason": "Nội dung tuyển dụng vi phạm quy định nền tảng"
}
```

`expectedJobVersion` is required and nonnegative. Strip surrounding whitespace from `reason`; it must contain 1–1000 Unicode code points after stripping. For client feedback, `Array.from(reason.trim()).length` counts code points rather than UTF-16 units; the server remains authoritative for whitespace/type validation. Send `Content-Type: application/json`.

Example HTTP 200 response (IDs, version and time are illustrative):

```json
{
  "status": 200,
  "message": "Success",
  "data": {
    "jobId": 42,
    "publicationStatus": "SUSPENDED",
    "jobVersion": 8,
    "actorUserId": 12,
    "suspendedAt": "2026-10-31T03:00:00Z",
    "reason": "Nội dung tuyển dụng vi phạm quy định nền tảng"
  }
}
```

Recommended interaction:

1. In an eligible job context, show **Ẩn tin tuyển dụng**. Open a confirmation dialog with a required reason and explanation that the posting becomes unavailable publicly while retained content/history remain accessible to authorized users.
2. Reload the Staff revision to obtain current `jobVersion`. Submit once using that token, and disable the submit button while the request is in flight.
3. On success, display **Đã ẩn tin tuyển dụng**, update the current job's UI to `SUSPENDED`, and refresh event history and affected revision/job data. Invalidate client-side public-detail/list caches for that job as well; HTTP cache headers do not clear an application's query cache.
4. On a conflict, retain the typed reason, reload current data and require the user to review the updated state before retrying. Do not silently replace the version and resubmit the action.

Only effectively public `PUBLISHED` jobs of verified, non-suspended Companies with a future approved deadline can be hidden. `PAUSED`, `CLOSED`, `EXPIRED`, `DRAFT` and already `SUSPENDED` jobs cannot be hidden. A deadline exactly equal to the current instant has already passed. Concurrent requests serialize: one can succeed, while a stale competitor receives 409.

The backend records the job transition and both audit records atomically. A successful HTTP response is the confirmation; if the connection fails before a response arrives, reload the job context/history to check the outcome rather than blindly retrying. There is no hide notification, delete, unhide or republish endpoint in this scope.

## UC-14: View posting-event history

`GET /api/staff/jobs/{jobId}/history?page=0&size=20`

This returns events, independently of the existing `/revisions` content/history endpoint. `page` is zero-based and nonnegative; `size` is 1–100 (default 20). Extremely large offsets are rejected. Events sort by `createdAt DESC, id DESC`.

```json
{
  "status": 200,
  "message": "Success",
  "data": {
    "content": [
      {
        "id": 301,
        "jobId": 42,
        "revisionId": null,
        "membershipId": null,
        "actorUserId": 12,
        "eventType": "JOB_SUSPENDED",
        "createdAt": "2026-10-31T03:00:00Z",
        "previousStatus": "PUBLISHED",
        "status": "SUSPENDED",
        "reason": "Nội dung tuyển dụng vi phạm quy định nền tảng"
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

Show **Lịch sử đăng tin** as a table or timeline with time, actor, action, recorded status transition and reason when present. Use `id` as the event key. Retain `jobId` and the originating route for **Quay lại tin tuyển dụng**; public navigation may be unavailable after hiding.

`actorUserId` identifies the recorded actor; the response does not include a display name or email. Display an ID when a name is not already available through an authorized existing source. `JOB_EXPIRED` can have a null actor and may display **Hệ thống**. `revisionId`, `membershipId`, `previousStatus`, `status` and `reason` are nullable. Display **—** for absent facts; do not infer a historical status or reason from today's posting/revision. Arbitrary audit payloads and private account details are not returned.

Handle these states independently:

| State | Suggested UI |
|---|---|
| Loading | Loading indicator without a false empty-history message. |
| HTTP 200, `totalElements === 0` | **Chưa có lịch sử đăng tin**. |
| HTTP 200, `content` empty but `totalElements > 0` | The page is out of range; return to a valid page. |
| HTTP error | **Không tải được lịch sử. Vui lòng thử lại.** with retry; do not display an empty-success state. |
| Success | Render only recorded events and pagination; no event edit/delete controls. |

Reading history makes no state change and does not persist deadline expiry. Offset paging uses a stable tie-breaker, but new events inserted between page requests can shift page boundaries; refresh from page zero after a mutation.

Event labels may map known values to Vietnamese. Provide a readable fallback for unknown values so future additive events do not break rendering:

| Event | Suggested label |
|---|---|
| `JOB_CREATED` | Tạo tin tuyển dụng |
| `JOB_DRAFT_SAVED` | Lưu bản nháp |
| `JOB_DRAFT_DERIVED` | Tạo bản nháp chỉnh sửa |
| `JOB_REVISION_SUBMITTED` | Gửi duyệt |
| `JOB_REVISION_WITHDRAWN` | Rút bản gửi duyệt |
| `JOB_REVISION_APPROVED` | Duyệt bản tin |
| `JOB_REVISION_REJECTED` | Từ chối bản tin |
| `JOB_PAUSED` | Tạm dừng tuyển dụng |
| `JOB_RESUMED` | Tiếp tục tuyển dụng |
| `JOB_CLOSED` | Đóng tin tuyển dụng |
| `JOB_EXPIRED` | Hết hạn tuyển dụng |
| `JOB_SUSPENDED` | Nhân viên nền tảng ẩn tin |

Responsibility and assignment events may also appear; use the fallback unless the client already defines labels. Render narrative/reason fields as text, never HTML. Format timestamps with `vi-VN` and `timeZone: "Asia/Ho_Chi_Minh"`; submit/store exact backend instants unchanged.

## Error handling

Failures retain the existing envelope, for example:

```json
{
  "status": 409,
  "message": "40022 CONCURRENT_MODIFICATION: Reload current versions",
  "data": null
}
```

| HTTP / message code | Frontend action |
|---|---|
| 400 | Fix malformed input, missing/nonnegative version, reason length or pagination. Show validation feedback without losing the reason. |
| 401 | Use the existing refresh/sign-in flow; do not automatically replay a destructive confirmation after an unknown result. |
| 403 | Staff/portal/account authority is unavailable. Remove action controls and show an access error. Company membership does not substitute for Staff authority. |
| 404 / `40478` | Job not found or public content unavailable. Clear stale public content and show an unavailable state. |
| 409 / `40022` | Version is stale. Reload and ask the user to review the current state before confirming again. |
| 409 / `40978` | Job is no longer eligible/public, including passed deadline or Company eligibility change. Reload and remove the hide action when ineligible. |
| 500 / network failure | Show retry/reload guidance; do not render success or empty history. Check current history/context after an uncertain mutation result. |

## Company and public application changes

Add `SUSPENDED` to the client's Job Posting status union and Vietnamese labels, distinct from Company-controlled `PAUSED`. The Company list accepts `GET /api/companies/me/jobs?status=SUSPENDED`; authorized Company reads still work. Honor the returned `availableActions`: suspended jobs cannot create/edit/submit drafts, pause/resume/close, or restore visibility. A retained draft can be previewed; a retained pending review may be withdrawn. Staff can still approve/reject a retained pending revision without clearing suspension.

A public `GET /api/jobs/{jobId}` for a suspended job returns 404 with no content, including for authenticated readers. Show **Tin tuyển dụng không còn khả dụng**; do not promise that every 404 means Staff suspension. Discard stale job content from application caches/service workers. Public detail now sends `Cache-Control: no-store` on successes and errors. Responses cached before deployment may require operational invalidation. There is no backend public search/listing endpoint or server content cache in this scope; any separately owned frontend index/cache must also respect unavailability.

## Frontend acceptance checklist

- A current active Staff session can hide an eligible posting with a reason; ordinary Users and Company-only roles cannot.
- Empty/overlong reasons are blocked; Unicode code points, whitespace, multiline Vietnamese text and API validation work correctly.
- Duplicate submits are disabled; stale versions and competing Staff actions reload safely without an automatic destructive retry.
- Successful hiding updates the UI, refreshes history and clears client public-content caches. Public access returns an unavailable screen without old content.
- History supports loading, empty, error/retry and pagination; same-time events retain deterministic order and null fields render safely.
- `SUSPENDED` is supported by Company/public clients, and retained pending-review decisions never suggest that the job was restored.
- Times use Vietnamese formatting in the Vietnam time zone; backend technical reasons are mapped to Vietnamese UI messages.
