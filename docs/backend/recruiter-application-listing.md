# Recruiter Application listing

`GET /api/companies/me/jobs/{jobId}/applications` lists submitted Applications for one Job Posting. Send an access token in the `Authorization: Bearer <accessToken>` header.

The Company Owner can view Applications for any Job Posting in their Company. A Recruiter needs an assignment for that Job Posting in their current active Company Membership period. Company Admin is excluded. Each request checks the live account, verified account email, active Company Membership, Company eligibility, and current Recruiter assignment. A former membership or assignment loses access immediately. A later membership period does not restore an assignment from an earlier period.

The Company must be verified and unsuspended. A suspended Job Posting is concealed from every Company Member, including the Owner, with the same `404` response used for an absent, other-Company, or unassigned Job Posting. These responses contain no Applications or result totals. Paused, closed, and expired Job Postings remain available for review while the other access checks pass.

## Query parameters

| Parameter | Default | Contract |
|---|---:|---|
| `search` | None | Optional partial match against the submitted Candidate name in the immutable Application Snapshot. Matching ignores case and Vietnamese accents, so `Nguyen` matches `Nguyễn`. Surrounding whitespace is trimmed; blank input means no filter. Up to 150 Unicode code points are accepted after trimming. `%` and `_` are treated as literal characters. |
| `status` | None | Optional exact status filter: `SUBMITTED`, `UNDER_REVIEW`, `SHORTLISTED`, `REJECTED`, or `WITHDRAWN`. Other values return `400`. Omitted status includes all five. |
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

`GET /api/companies/me/jobs/{jobId}/applications/{applicationId}` returns the immutable submitted name, email, phone, cover letter, job title, Company name, current status/version and CV metadata. The Candidate-owned `GET /api/candidate/applications/{applicationId}` returns their own submitted evidence with the same envelope. Both responses use `Cache-Control: no-store`; opening details does not change status or send a notification. Company authorization and non-enumerating failures match the listing. Company personal details are removed after `REJECTED` or `WITHDRAWN`; the Candidate can continue to view their own details. CV bytes are not returned by these endpoints.

`GET /api/companies/me/jobs/{jobId}/applications/{applicationId}/cv` returns the application-specific PDF copy as an inline binary response. DOC and DOCX snapshots return `406` because this endpoint only views PDFs. Authorization is checked on each request, terminal Applications return a concealed 404, and successful reads create an `APPLICATION_CV_VIEWED` audit event. The response uses `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`; neither a storage key nor a signed provider URL is sent to the client. The backend fetch is limited to 10 MiB and uses provider-generated HTTPS URLs with bounded connect/read timeouts. An inline response cannot prevent a browser from saving or copying the CV.

## Review status and Candidate withdrawal

Company members use `PATCH /api/companies/me/jobs/{jobId}/applications/{applicationId}/status` with `status`, `version`, and rejection fields only for `REJECTED`. Active statuses are `SUBMITTED`, `UNDER_REVIEW`, and `SHORTLISTED`; actors may advance or skip forward, and reject from any active status. `OTHER` rejection requires `internalExplanation`; optional `candidateFeedback` is stored separately. Text is trimmed and limited to 1000 Unicode code points. A same-status request with the current version is an idempotent no-op. Stale versions return 409. Candidate feedback never includes internal reasons in Candidate detail or notification data.

Candidates withdraw with `POST /api/candidate/applications/{applicationId}/withdraw` and `{ "version": n }`. `REJECTED` and `WITHDRAWN` are terminal. Current Owner and assigned Recruiters receive deduplicated in-app withdrawal notifications. Candidate notifications cover stage changes and rejection. Notification intents commit with status history and are delivered by the existing retry worker; notification context includes the Application ID and only safe Candidate feedback.

Candidate list responses include the current `version` and Candidate-safe latest `candidateFeedback`, so clients can submit a guarded mutation. Candidate detail contains only safe history; Company detail contains actor attribution and internal rejection details. The Company list now accepts all five statuses; an omitted status returns all. Search, ordering and pagination are unchanged.

Migration `V2026.11.03.101__application_review_history_and_notifications.sql` expands allowed states, creates append-only Application Status History, backfills each legacy `SUBMITTED` row as a submission event, and adds Application context to notification storage. It deliberately fails when legacy `VIEWED` or `REJECTED` rows exist, since their actor/time/reason cannot be reconstructed. Inspect and reconcile those records before deployment; do not invent history. No snapshot is modified and no dependency is added.
