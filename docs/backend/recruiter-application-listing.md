# Recruiter Application listing

`GET /api/companies/me/jobs/{jobId}/applications` lists submitted Applications for one Job Posting. Send an access token in the `Authorization: Bearer <accessToken>` header.

The Company Owner can view Applications for any Job Posting in their Company. A Recruiter needs an assignment for that Job Posting in their current active Company Membership period. Company Admin is excluded. Each request checks the live account, verified account email, active Company Membership, Company eligibility, and current Recruiter assignment. A former membership or assignment loses access immediately. A later membership period does not restore an assignment from an earlier period.

The Company must be verified and unsuspended. A suspended Job Posting is concealed from every Company Member, including the Owner, with the same `404` response used for an absent, other-Company, or unassigned Job Posting. These responses contain no Applications or result totals. Paused, closed, and expired Job Postings remain available for review while the other access checks pass.

## Query parameters

| Parameter | Default | Contract |
|---|---:|---|
| `search` | None | Optional partial match against the submitted Candidate name in the immutable Application Snapshot. Matching ignores case and Vietnamese accents, so `Nguyen` matches `Nguyễn`. Surrounding whitespace is trimmed; blank input means no filter. Up to 150 Unicode code points are accepted after trimming. `%` and `_` are treated as literal characters. |
| `status` | None | Optional exact status filter: `SUBMITTED`, `UNDER_REVIEW`, `SHORTLISTED`, `INTERVIEWING`, `HIRED`, `REJECTED`, or `WITHDRAWN`. Other values return `400`. Omitted status includes every status. |
| `page` | `0` | Zero-based page number. Must be nonnegative and must not produce a pagination offset above `Integer.MAX_VALUE`. |
| `size` | `20` | Page size from `1` through `100`. |

Search and status filters combine before database pagination. Results are ordered by `appliedAt` descending, then Application ID descending. An empty result or a page beyond the last result returns `200` with an empty `content` array and the accurate matching total.

## Success response

The response uses the existing `{ status, message, data }` envelope and `PageResponse` fields. Each row contains only the Application identifier, submitted Candidate name, current status, and submission time:

```json
{
  "status": 200,
  "message": "Applications retrieved successfully",
  "data": {
    "content": [
      {
        "id": "b0dba9a4-857f-4d1a-8dd4-23f5a5dc5e35",
        "candidateName": "Nguyễn Văn An",
        "status": "SUBMITTED",
        "appliedAt": "2026-10-31T12:00:00Z"
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

For example, search by an unaccented name and retain Applications awaiting review with:

```text
GET /api/companies/me/jobs/42/applications?search=Nguyen&status=UNDER_REVIEW&page=0&size=20
```

Invalid search, status, or pagination values return `400` with the existing Company API error envelope. Requests without authentication return `401`. Missing active Company Membership and excluded Company roles retain their existing scoped `403` errors. Absent, inaccessible, unassigned, or suspended Job Postings return a non-enumerating `404`.

The list does not expose email, phone, cover letter, CV information, Candidate profile identity, or storage identifiers. Reading Applications sends no notifications and records no business audit event. The list does not log Candidate names or search text; request summaries use the existing route template and omit query parameters as defined in the [backend logging standard](../convention/backend-logging.md).

## Application details

`GET /api/companies/me/jobs/{jobId}/applications/{applicationId}` returns the immutable submitted name, email, phone, cover letter, job title, Company name, current status/version and CV metadata. The Candidate-owned `GET /api/candidate/applications/{applicationId}` returns their own submitted evidence with the same envelope. Both responses use `Cache-Control: no-store`; opening details does not change status or send a notification. Company authorization and non-enumerating failures match the listing. Company personal details are removed after `REJECTED` or `WITHDRAWN`; they remain available for `HIRED` while current live authorization passes. The Candidate can continue to view their own details. CV bytes are not returned by these endpoints.

`GET /api/companies/me/jobs/{jobId}/applications/{applicationId}/cv` returns the application-specific PDF copy as an inline binary response. DOC and DOCX snapshots return `406` because this endpoint only views PDFs. Authorization is checked on each request; `REJECTED` and `WITHDRAWN` Applications return a concealed 404, while a `HIRED` Application remains available under the same live authorization checks. Successful reads create an `APPLICATION_CV_VIEWED` audit event. The response uses `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`; neither a storage key nor a signed provider URL is sent to the client. The backend fetch is limited to 10 MiB and uses provider-generated HTTPS URLs with bounded connect/read timeouts. Cloudinary `privateDownload` URLs target `api.cloudinary.com/v1_1/{cloudName}/{resourceType}/download`, rather than the `res.cloudinary.com` delivery CDN. The backend validates the exact API host, configured cloud/resource path, HTTPS and default HTTPS port, rejects userinfo, and does not follow redirects. Signed query parameters remain server-side. An inline response cannot prevent a browser from saving or copying the CV.

## Review status and Candidate withdrawal

Company members use `PATCH /api/companies/me/jobs/{jobId}/applications/{applicationId}/status` with `status`, `version`, and rejection fields only for `REJECTED`. Active statuses are `SUBMITTED`, `UNDER_REVIEW`, `SHORTLISTED`, and `INTERVIEWING`. Companies may move `UNDER_REVIEW` or `SHORTLISTED` to `INTERVIEWING`; the status records progression toward interviews arranged outside GuardWork and does not claim that a meeting is scheduled. Companies may move `UNDER_REVIEW`, `SHORTLISTED`, or `INTERVIEWING` to `HIRED` only after confirming the Candidate accepted. Send the optional Boolean `hiringConfirmed: true` with a `HIRED` request, including a same-status retry; a missing or false confirmation is invalid. A non-null `hiringConfirmed` value is invalid for every other target status. Omitting the field remains valid for existing requests. `HIRED`, `REJECTED`, and `WITHDRAWN` are terminal. They may reject an Application from any active status, and Candidates may withdraw from any active status. `OTHER` rejection requires `internalExplanation`; optional `candidateFeedback` is stored separately. Text is trimmed and limited to 1000 Unicode code points. A same-status request with the current version is an idempotent no-op, provided the HIRED confirmation rule is met. Stale versions return 409. Candidate feedback never includes internal reasons in Candidate detail or notification data.

Candidates withdraw with `POST /api/candidate/applications/{applicationId}/withdraw` and `{ "version": n }`. `REJECTED` and `WITHDRAWN` are terminal. Current Owner and assigned Recruiters receive deduplicated in-app withdrawal notifications. Candidate notifications cover stage changes, hiring, and rejection. Notification intents commit with status history and are delivered by the existing retry worker; notification context includes the Application ID and only safe Candidate feedback.

Candidate list responses include the current `version` and Candidate-safe latest `candidateFeedback`, so clients can submit a guarded mutation. Candidate detail contains only safe history; Company detail contains actor attribution and internal rejection details. Company and Candidate lists accept `INTERVIEWING` and `HIRED` as status filters and return them in rows and details. Search, ordering and pagination are unchanged.

An interview-stage notification uses event type `APPLICATION_INTERVIEWING`, is addressed to the Candidate, and carries only the existing Company, Job Posting and Application context. Clients should render the standard Vietnamese message: “Hồ sơ của bạn đã chuyển sang vòng phỏng vấn. Nhà tuyển dụng sẽ liên hệ để trao đổi chi tiết.” It conveys that the Company will contact the Candidate externally. Do not attach a custom message, schedule, location, CV, contact details, or internal rejection information. The existing notification API returns the event type and safe context; a link to an Application must still use the live authorized detail endpoint.

A hiring notification uses event type `APPLICATION_HIRED`, is addressed to the Candidate, and carries only the existing Company, Job Posting and Application context. Clients should render: “Công ty đã xác nhận bạn đồng ý gia nhập. Nhà tuyển dụng sẽ liên hệ với bạn về các bước tiếp theo.” Do not attach a custom message, CV, contact details, or internal rejection information. Delivery is deduplicated by the existing notification mechanism; a link to an Application must still use the live authorized detail endpoint.

Migration `V2026.11.03.101__application_review_history_and_notifications.sql` creates append-only Application Status History, backfills each legacy `SUBMITTED` row as a submission event, and adds Application context to notification storage. It deliberately fails when legacy `VIEWED` or `REJECTED` rows exist, since their actor/time/reason cannot be reconstructed. Inspect and reconcile those records before deployment; do not invent history. Migration `V2026.11.10.103__application_interview_stage.sql` adds `INTERVIEWING` and its allowed history edges and notification event to the existing constraints. Migration `V2026.11.10.104__application_hiring_confirmation.sql` adds `HIRED`, its allowed history edges and notification event to existing constraints. These migrations retain prior Application snapshots and history. No dependency is added.

## Company-wide Application inbox

`GET /api/companies/me/applications` returns Applications across eligible Company Job
Postings. Send the same Bearer access token as the per-job list. Owners can read all
eligible Company Job Postings; Recruiters see only Job Postings assigned to their
current active membership period. Admins are excluded. Live account, verified email,
Company membership, verification and suspension checks remain unchanged. Suspended
Job Postings are omitted from both rows and totals. Paused, closed and expired Job
Postings remain eligible.

The endpoint accepts the same `search`, `status`, `page` and `size` parameters and
validation as the per-job list, plus optional positive `jobId`. An inaccessible,
absent or suspended `jobId` returns the existing concealed 404 with no rows or totals.
Without that filter, no accessible matches returns 200 with an empty page. Filtering
and assignment authorization run before database pagination and counting. Ordering
is `appliedAt DESC, id DESC` across all matching jobs.

Each row contains `id`, `candidateName`, `status`, `appliedAt`, `jobId` and `jobTitle`.
The title comes from the immutable Application Snapshot, matching the detail API.
The response retains `ApiResponse<PageResponse<...>>` and sets `Cache-Control: no-store`.
It exposes no contact, CV, profile or storage data and produces no workflow changes,
notifications or business audit events.

Example: `GET /api/companies/me/applications?status=SUBMITTED&search=Nguyen&page=0&size=20`.
Use the returned `jobId` and `id` with the existing per-job detail and CV routes.
The existing per-job response is unchanged.

No migration, new index, configuration or dependency is introduced. The query can
use the existing Company/time, job/time and active membership/job assignment indexes.
Exact totals, substring name search and deep offsets can remain expensive at high
volume. Index presence alone is not a production scale guarantee; validate page and
count plans with representative Company sizes before claiming a capacity target.


### History actor display names

Company detail and status-update history entries include additive nullable `actorName`
next to the existing `actorUserId`. It is the actor's current account full name, falling
back to their username when the full name is blank. Names are resolved in one batch
only after the existing Company authorization checks. Missing actors have a null name;
clients should show a neutral unavailable label rather than a database ID. Attribution
remains visible in terminal workflow history; contact, cover-letter and CV redaction is
unchanged. Candidate-owned history exposes neither actor IDs nor actor names. No schema
migration, dependency, or history mutation is introduced; this is current display
attribution, not a historical name snapshot.
