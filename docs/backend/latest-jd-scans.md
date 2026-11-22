# Restoring saved JD analysis

The AI panels discover retained scans with these authenticated read-only routes:

```text
GET /api/companies/me/jobs/{jobId}/revisions/{revisionId}/scans/latest?expectedRevisionVersion={version}
GET /api/staff/job-revisions/{revisionId}/scans/latest?expectedRevisionVersion={version}
```

Both return the existing scan-view envelope, or HTTP 200 with `data: null` when
there is no associated scan matching the current saved content. They use
`Cache-Control: no-store`. Numeric `/scans/{scanId}` reads remain the polling routes.

Recruiter discovery reuses current Company membership and job-assignment checks.
Staff discovery requires current Staff authority and a submitted revision; drafts
remain concealed. The backend checks the requested locking version before lookup:
missing/negative versions return 400, a changed version returns 409, and inaccessible
resources retain the existing 401/403/404 behavior. Matching requires the same
Company, job, revision, and canonical content hash. The newest association wins,
including queued, running, completed, or failed scans. A state-only version change
may retain a scan of identical content; removed draft text is never restored.
Older analysis configuration/date is still identified by `outdated`.

Reads do not create associations, queue scans, retry failed work, or contact the AI
adapter. This fixes the missing backend routes expected by the existing frontend;
there is no schema, dependency, or scan-creation change. This differs from the
candidate-facing public `aiAnalysis` projection, which selects completed scans only.

Recruiter and Staff lookup/polling errors use `scanReadError` rather than the job
content-validation formatter. HTTP 400 during loading says analysis could not be
loaded, while real save/validation errors preserve the job-content messages.
Authentication, authorization, unavailable results, version conflicts, and service
outages each retain appropriate recovery guidance. Existing candidate browsing is
unchanged.

Verification uses backend `JdLatestScanHttpTest`, frontend `jobScans.test.mjs`, and
desktop recruiter/Staff browser regressions for lookup failures and saved-result
restoration. Backend HTTP tests use PostgreSQL and never require a live AI provider.
