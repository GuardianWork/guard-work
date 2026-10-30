# Staff account management: frontend integration

GW-92 implements the backend for UC-18 (view account), UC-19 (ban), and UC-20
(unban). Follow the [scoped contract](../software-requirements/adm/account-management-spec.md)
for policy and field visibility. No frontend application is included in this repository.

## Authentication and routes

Use the existing Staff login/proxy and token storage. Send `Authorization: Bearer
<accessToken>`; the Staff proxy must overwrite `X-Guardian-Portal: staff`. Platform
`STAFF` is required. Company `ADMIN`, `OWNER`, and `RECRUITER` do not grant Staff access.
Never send an actor ID or role in the request body.

Choose `type=users` or `type=companies` in these routes:

| Method | Route | Purpose |
|---|---|---|
| GET | `/api/staff/accounts/{type}?q=&page=0&size=20` | Search/list targets |
| GET | `/api/staff/accounts/{type}/{id}` | Load current target details |
| POST | `/api/staff/accounts/{type}/{id}/ban` | Apply a ban |
| POST | `/api/staff/accounts/{type}/{id}/unban` | End an active managed ban |

Search is a case-insensitive literal substring across username/email/first/last name
for users, or name/tax code for Companies. A positive numeric query also matches ID.
Blank search lists all targets. URI-encode `q`, limit it to 200 characters, reset
`page` to zero when changing query/type, and ignore stale responses from previous
searches. `page` starts at zero; `size` is 1–100, default 20. Order is descending ID.

## Response shapes

Successful responses use `{ "status": 200, "message": "...", "data": ... }`.
Search `data` uses the existing page shape:

```json
{
  "content": [],
  "pageNumber": 0,
  "pageSize": 20,
  "totalElements": 0,
  "totalPages": 0,
  "first": true,
  "last": true
}
```

Each search row has the same shape as its target's detail response. User fields:

```json
{
  "id": 123,
  "username": "example_candidate",
  "firstName": "An",
  "lastName": "Nguyễn",
  "email": "candidate@example.vn",
  "emailVerified": true,
  "role": "USER",
  "status": "BANNED",
  "createdAt": "2026-10-30T01:00:00Z",
  "updatedAt": "2026-10-30T02:00:00Z",
  "activeBan": {
    "id": 45,
    "reason": "Recorded violation",
    "startedAt": "2026-10-30T02:00:00Z",
    "expiresAt": "2026-11-06T02:00:00Z",
    "createdBy": 9
  },
  "canBan": false,
  "canUnban": true
}
```

Company fields are `id`, `name`, `taxCode`, `verificationStatus`, `isBanned`,
`createdAt`, `updatedAt`, `activeBan`, `canBan`, and `canUnban`.
Verification and enforcement are independent: a verified Company may be banned.
`activeBan=null` means no current managed ban; `activeBan.expiresAt=null` means
permanent. Do not infer action eligibility solely from these fields: use the
server's `canBan` and `canUnban` flags. Legacy inactive targets may have neither
action available. Staff cannot ban their own user account.

## Screen and action flow

1. Show target-type selection, search, and paginated results. A row opens detail;
   retrieve detail again when opening it to avoid acting on stale search data.
2. Show only the returned identifying fields, effective status, and active-ban
   reason/dates. Never expect credentials, sessions, private documents, or messages.
3. Offer “Khóa tài khoản” only when `canBan=true`, and “Mở khóa tài khoản” only when
   `canUnban=true`. Show loading, empty results, not-found, and retry states.
4. Ban opens a confirmation form with a required reason and temporary/permanent
   choice. A temporary ban requires a future expiry instant. Explain Company scope:
   active members must sign in again and public postings will be suspended.
5. Unban opens a confirmation form with a required reason. Explain that old sessions
   remain invalid and Company postings are not automatically restored.
6. Disable repeated submission while pending. On HTTP 200, replace detail with
   response `data`, reload the current result page, close the form, and show success.
   On failure, retain entered text and display a localized error. Do not optimistically
   change enforcement state or automatically retry a mutation.

Ban payload (temporary):

```json
{ "reason": "Recorded violation", "expiresAt": "2026-11-06T02:00:00Z" }
```

Ban payload (permanent):

```json
{ "reason": "Recorded violation", "expiresAt": null }
```

Unban payload:

```json
{ "reason": "Appeal accepted" }
```

Reasons must contain 1–2000 characters after trimming. Only send the documented
fields; extra fields are rejected. Send dates as ISO-8601 instants, for example
`date.toISOString()`, and display them using `vi-VN` / `Asia/Ho_Chi_Minh`. Backend
validation remains authoritative when an expiry elapses while a request waits.

## Errors, retries, and sessions

Errors on these routes retain `{ "status": <httpStatus>, "message": "...", "data": null }`.
Localize UI messages by status and the documented identifier; do not display raw
server messages as Vietnamese product copy.

| HTTP | Identifier / interpretation | Frontend behavior |
|---|---|---|
| 400 | `40092 INVALID_ACCOUNT_INPUT` | Keep form/search values; correct invalid input |
| 401 | Unauthenticated, expired, or invalidated session | Use existing login/refresh handling; if refresh is revoked, clear credentials and require sign-in |
| 403 | `40392 SELF_BAN` or existing Staff/portal denial | Explain refusal; do not change the target |
| 404 | `40492` target/type/action unavailable | Remove stale detail; allow return to search |
| 409 | `40992 ACCOUNT_STATE_CONFLICT` | Reload detail and eligibility; explain concurrent/state change |
| 503 | `50392 ENFORCEMENT_UNAVAILABLE` | Show a retryable availability error; reload detail before a deliberate resubmission |
| 500 | Unexpected failure | Show failure and allow retry after reloading detail |

A lost network response leaves the outcome unknown: reload detail before offering
resubmission. Do not show success unless a successful response is received or the
updated state is subsequently confirmed by a read.

User ban/unban invalidates all existing access and refresh credentials. Company
ban/unban invalidates every active member role, while personal accounts remain
active: members may sign in again for permitted personal access during a Company
ban. Company access remains restricted, with the existing Owner status/evidence
read exception. A Staff actor who is also a member of the target Company can
therefore invalidate their own session through a Company action.

Temporary bans expire without a UI unban request. Reload details on focus or before
an action to refresh effective status; no client timer should grant access. Expiry
never revives old credentials. Company expiry also invalidates sessions created
during its ban. Both unban and expiry leave Staff-suspended postings hidden; UC-21
restoration is a separate flow. Public posting detail returns 404 for those postings.
