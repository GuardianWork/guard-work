# UC-22: Staff personal activity history — frontend integration

Implemented backend: `GET /api/staff/me/activities` (GW-104, GW-SR-ADM-03).
This repository delivers the API; implement the Staff page in the frontend repository.
The requesting human authorized implementation of the proposed UC-22 contract in the
GW-104 session on 2026-11-02. The source documents retain their Draft lifecycle
metadata pending formal named approval; this implementation does not claim that
formal review has occurred.

## Authentication

Use the existing Staff proxy and Bearer access token. The proxy supplies
`X-Guardian-Portal: staff`. No user/actor ID is accepted: the server selects the
currently authenticated Staff member. Company roles alone do not grant access.
Apply existing session recovery on 401 and access denial on 403.

## Screen and filters

Title: **Lịch sử hoạt động của tôi**. Render a read-only table with occurrence time,
action, target type/code, previous state, resulting state, and recorded reason.
Render null values as **Không có dữ liệu**. Treat reasons as plain text. Provide
loading, empty (**Không có hoạt động phù hợp**), invalid-input, access-denial and
retry states. A failed request must not display an empty-success state.

Initialize both date controls to the most recent 30 Vietnam calendar dates,
including today. Interpret/display dates and times in `Asia/Ho_Chi_Minh`, with
`vi-VN` formatting. Send both dates as `YYYY-MM-DD`, or omit both to use the server
default. Explicit historical and future ranges are supported; start must not exceed
end. Avoid deriving these dates with UTC `toISOString().slice(0,10)`.

All filters intersect. Send only one occurrence of each supported parameter:

| Parameter | Values |
| --- | --- |
| `fromDate`, `toDate` | Both supplied or both omitted; inclusive calendar dates |
| `category` | `ACCOUNT_MANAGEMENT`, `JOB_MODERATION`, `COMPANY_VERIFICATION` |
| `action` | See action table below |
| `targetType` | `USER`, `COMPANY`, `JOB_POSTING` |
| `targetId` | Positive decimal ID; requires category and targetType |
| `page` | Zero-based integer, default 0 |
| `size` | Omit or send 20 |

No text/name search or custom sort exists. Reset page to zero when any filter
changes. Keep target IDs as strings to avoid JavaScript integer precision loss.
When category/action changes, clear incompatible type/action/target selections.
Account management supports USER or COMPANY; job moderation supports JOB_POSTING;
Company verification supports COMPANY. A target type may be used without an ID.

| Category | Vietnamese label |
| --- | --- |
| `ACCOUNT_MANAGEMENT` | Quản lý tài khoản |
| `JOB_MODERATION` | Kiểm duyệt tin tuyển dụng |
| `COMPANY_VERIFICATION` | Xác minh doanh nghiệp |

| Action | Vietnamese label |
| --- | --- |
| `BAN_ACCOUNT` | Cấm tài khoản |
| `UNBAN_ACCOUNT` | Gỡ cấm tài khoản |
| `APPROVE_JOB_REVISION` | Duyệt phiên bản tin tuyển dụng |
| `REJECT_JOB_REVISION` | Từ chối phiên bản tin tuyển dụng |
| `APPROVE_COMPANY` | Duyệt xác minh doanh nghiệp |
| `REJECT_COMPANY` | Từ chối xác minh doanh nghiệp |
| `REVIEW_COMPANY` | Xét duyệt doanh nghiệp (thiếu kết quả lịch sử) |

## Request and response

```http
GET /api/staff/me/activities?fromDate=2026-10-01&toDate=2026-10-30&category=ACCOUNT_MANAGEMENT&targetType=USER&targetId=123&page=0
Authorization: Bearer <accessToken>
```

```json
{
  "status": 200,
  "message": "Activity history retrieved successfully",
  "data": {
    "content": [{
      "id": 501,
      "occurredAt": "2026-10-15T00:00:00Z",
      "category": "ACCOUNT_MANAGEMENT",
      "action": "BAN_ACCOUNT",
      "targetType": "USER",
      "targetId": "123",
      "revisionId": null,
      "changes": [{"field": "status", "before": "ACTIVE", "after": "BANNED"}],
      "reason": "Recorded reason"
    }],
    "pageNumber": 0,
    "pageSize": 20,
    "totalElements": 1,
    "totalPages": 1,
    "first": true,
    "last": true
  }
}
```

Use `content` as rows and `first`/`last` to control pagination. Ordering is fixed:
occurrence time descending, then event ID descending. Concurrent new events may
shift offset pages; refreshing returns the newest data. An out-of-range page is a
successful empty result with retained totals.

`changes` contains one dimension per event: USER `status` (`ACTIVE`, `SUSPENDED`,
`DELETED`, `BANNED`), COMPANY account `isBanned` (boolean), Company
`verificationStatus` (`PENDING`, `VERIFIED`, `REJECTED`), or job `reviewState`
(`DRAFT`, `PENDING_REVIEW`, `APPROVED`, `REJECTED`, `WITHDRAWN`). Localize values;
APPROVED is a review result, not a claim that a job is currently published.

`targetId` for moderation is the Job Posting ID. `revisionId` is the distinct
reviewed revision code. Missing immutable identity leaves targetId null. Old audit
snapshots may have null before/after values; display these honestly. Rows describe
historical actions, even when current targets have changed or disappeared.
Approval reasons are null; generated audit fallback prose is not a submitted reason.

No edit/delete/export, raw JSON viewer, target navigation, or detail endpoint is
provided. Do not add controls implying these capabilities.

## Errors

The scoped response envelope is `{status, message, data}`. Localize from HTTP
status rather than matching English prose. Invalid filters return 400; preserve
input and show validation feedback. Unsupported parameters (including actorId,
staffId, userId), duplicate parameters, invalid enums/date ranges, incompatible
filters and sizes other than 20 are rejected. Storage failures return 503; offer
**Thử lại**. Unexpected failures return 500. Never render response messages as HTML.
Cancel stale fetches or ignore late responses after filter changes.

## Backend rollout

No schema, dependency, retention or auth architecture change is required. Existing
audit rows stay immutable. New job moderation audit entries record safe reviewState
before/after values inside the existing mutation transaction. Other current history
consumers retain their outcome field. Reads do not write successful-action audits.
