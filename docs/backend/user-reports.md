# GW-99 user reports — backend integration

The [scoped contract](../software-requirements/adm/user-report-intake-contract.md)
was approved by the requesting maintainer on 2026-11-01. Report inspection is
read-only: opening a report does not start review or apply sanctions.

## Submit

An active public Platform USER sends Bearer authentication to `POST /api/reports`.
No verified-email or Company membership prerequisite is added. Use multipart/form-data:

- `report`: application/json containing exactly `targetType`, `targetId`, `complaint`.
- `file`: optional repeated file part, at most three files, each at most 5 MiB.

```json
{"targetType":"COMPANY","targetId":"42","complaint":"Reported violation details"}
```

`targetType` is COMPANY, JOB or CANDIDATE. Target IDs are strings: positive decimal
Long for Companies/Jobs, canonical UUID for Candidate profiles. Target must be
publicly available. Missing/private/unavailable targets return the same 404.
Trimmed complaints contain 10–5000 Unicode code points. Evidence must be nonempty
PDF, PNG or JPEG whose signature matches its declared media type. Duplicate file
contents in one submission are refused. Signature validation is not malware scanning.
The JSON part is independently capped at 32 KiB before parsing; the report servlet
allows at most 16 MiB for the complete multipart request, including overhead.

Successful HTTP 201 wraps `id`, `targetType`, `targetId`, `status:OPEN`, `createdAt`
in the existing ApiResponse. This is a receipt, not a decision. Do not automatically
retry submission after a lost response; repeat requests create distinct reports.
There is no reporter history/detail endpoint in this scope.

## Staff inspection

Use existing Staff authentication and trusted `X-Guardian-Portal: staff` routing.
Company roles alone grant no Staff authority.

| Method | Route | Purpose |
|---|---|---|
| GET | `/api/staff/reports?status=ALL&targetType=ALL&page=0&size=20` | Filtered summaries |
| GET | `/api/staff/reports/{id}` | Report detail and evidence metadata |
| GET | `/api/staff/reports/{id}/evidence/{evidenceId}` | Private attachment download |

Status filters: ALL, OPEN, IN_REVIEW, RESOLVED, REJECTED. Target filters: ALL,
COMPANY, JOB, CANDIDATE. Paging is zero-based with size 1–100, default 20, newest
first with ID as tie-breaker. New submissions can shift offset pages. Reset paging
when filters change. Empty content and failed retrieval are different UI states.

Summaries contain receipt fields plus complaint, targetLabel and targetAvailable.
Detail adds reporter userId/displayName, nullable processingOutcome, and evidence
metadata: id, safe filename, contentType, sizeBytes, available and downloadPath.
Unavailable targets have no label; the complaint/reference remain. Missing
evidence has available=false and no download path while remaining details load.
Reporter email, phone, credentials and unrelated private profile data are excluded.

Downloads use attachment disposition, no-store and nosniff. The evidence must
belong to the report in the URL. Do not expose download URLs publicly or prefetch
them without Staff authentication. Render all supplied text as plain text. Format
times using vi-VN and Asia/Ho_Chi_Minh. Reads and downloads offer no resolve/reject,
review-lock, sanction or status-edit controls.

## Errors and rollout

Errors preserve scoped ApiResponse behavior and documented semantic identifiers:
400 INVALID_REPORT_INPUT, 404 REPORT_TARGET_UNAVAILABLE / REPORT_NOT_FOUND /
REPORT_EVIDENCE_NOT_FOUND, and 413 REPORT_CONTENT_TOO_LARGE. Existing 401/403 account,
role and portal handling still applies. Unexpected persistence/audit errors are
failures; never display an empty-success view or success receipt for them.

Apply the additive migrations `V2026.11.01.99__user_report_intake.sql` and
`V2026.11.01.100__user_report_evidence.sql` before deploying the backend. They create
private report/evidence tables without changing existing records. Report, evidence,
and safe submission audit commit atomically. No external storage/provider,
notification, new dependency, deletion processor, or retention schedule is added.
Production abuse-rate policy and retention remain separately scoped follow-up work.

HTTP/PostgreSQL tests verify backend behavior. Browser rendering, production
deployment, and actual frontend integration are separate acceptance checks.
