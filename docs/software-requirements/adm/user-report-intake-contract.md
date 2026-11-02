# GW-99 — User Report Intake and Staff Inspection

| Field | Value |
|---|---|
| Specification ID | GW-SR-ADM-REP-99 |
| Ticket reference | GW-99; tracker title unavailable, no tracker connection or issue URL supplied |
| Version | 0.1.0 |
| Status | Draft lifecycle label — implementation scope explicitly approved on 2026-11-01 |
| Owner | GuardWork thesis team |
| Human approver | Requesting maintainer; explicit session approval on 2026-11-01; display name not supplied |
| Last updated | 2026-11-01 |

## 1. Purpose and scope

The requesting maintainer explicitly answered "yes" to this complete contract on
2026-11-01, including its additive database schema and API/privacy/file-validation
rules. The agent retains the Draft lifecycle label and does not assign formal
Approved status. This scoped approval does not approve the remainder of the Staff
draft or upcoming-sprint work.

Complete the missing current-task backend for receiving user reports and Staff
inspection under UC-11–12 / ADM-REP-01,08–11 in the [Staff draft](../../feat/staff/spec.md).
Submission is included in the scoped approval so inspection works with real user
input; it does not imply approval of report adjudication.

Existing authentication, OTP, account enforcement, Company verification and job
moderation remain governed by their scoped approved contracts. Automatic MST
lookup, job restoration, dedicated all-job Staff search, personal activity history,
report adjudication, sanctions, email notifications, and frontend work are excluded.

## 2. Authority and privacy

- Submission requires an authenticated active Platform USER in the public portal,
  with existing account-enforcement rules. Verified email and Company membership
  are not additional prerequisites. Staff accounts cannot submit through this API.
- Staff list/detail/evidence require an active persisted Platform STAFF and the
  existing Staff portal rules. Recheck current actor state in the service.
- Reporter identity comes exclusively from authentication. Requests cannot supply
  a reporter, status, processing outcome, reviewer, or timestamp.
- Staff detail may reveal reporter user ID and current full name only; no reporter
  email, phone, CV, credentials, sessions, or unrelated private profile data.
- Reports and evidence are never public and are not disclosed to reported targets.
  This scope supplies no reporter history/detail/download API after submission.
- Reads do not change report state, acquire a processing lock, or apply sanctions.
  Ordinary transactional read consistency is not a processing lock.

## 3. Proposed API contract

Reuse ApiResponse/PageResponse and scoped exception mapping; no shared architecture
change or new dependency. Accept only documented submission JSON properties.

| Method | Route | Result |
|---|---|---|
| POST | `/api/reports` | Multipart submission; HTTP 201 receipt after commit |
| GET | `/api/staff/reports` | Paginated filtered summaries |
| GET | `/api/staff/reports/{id}` | Private detail with evidence metadata |
| GET | `/api/staff/reports/{id}/evidence/{evidenceId}` | Authorized attachment bytes |

POST uses one JSON `report` part and zero to three repeated `file` parts.

```json
{
  "targetType": "JOB",
  "targetId": "42",
  "complaint": "Description of the reported violation"
}
```

Target type is COMPANY, JOB or CANDIDATE. Company/Job IDs are positive Long values
encoded as strings. Candidate IDs are canonical profile UUID strings, matching the
existing candidate_profiles primary key rather than assuming a numeric profile ID.
Trim complaint text; require 10–5000 Unicode code points. Target existence and
visibility must be checked without leaking private targets: Companies must be
verified and non-banned; Jobs must satisfy the existing public detail eligibility;
Candidates must have public profiles and active owning users. Private/unavailable
and nonexistent targets produce the same 404. Do not require a previous application
or membership. Repeated submissions create separate reports; no automatic replay or
deduplication promise is introduced.

Receipt fields: `id`, `targetType`, `targetId`, `status`, `createdAt`. Newly submitted
reports are OPEN. Receipt never echoes reporter identity or evidence bytes.

List parameters: `status=ALL`, `targetType=ALL`, `page=0`, `size=20`. Valid status
filters: ALL, OPEN, IN_REVIEW, RESOLVED, REJECTED; valid target filters: ALL,
COMPANY, JOB, CANDIDATE. Reject invalid values, negative page, size outside 1–100,
or offsets beyond Integer.MAX_VALUE. Order createdAt DESC then id DESC. Empty/out-of-
range results are successful empty content, retaining the actual total count.

Summary fields: receipt fields plus `complaint`, `targetLabel`, `targetAvailable`.
Resolve labels from current authorized data; an unavailable target has null label
and false availability, preserving the report. Do not copy private job revisions or
Candidate contact fields into report responses.

Detail adds `reporter: { userId, displayName }`, `processingOutcome` (nullable),
and `evidence` metadata: `id`, safe `filename`, `contentType`, `sizeBytes`,
`available`, and a relative authorized download path when available. Missing
reporter display data is null; do not invent identity. Existing retained outcomes
are displayed as plain text, never executed or interpreted as sanctions. No report
status/outcome update endpoint is introduced.

## 4. Evidence bounds and handling

Propose the existing PostgreSQL private-byte storage pattern: no external storage
dependency, remote URL fetching, arbitrary HTML, SVG, or office documents. Accept
PDF, PNG and JPEG with signature/type agreement, maximum 5 MiB per file and three
files. Reject empty files and duplicate file content within one submission.
Apply scoped multipart limits with sufficient overhead for a 15 MiB evidence total
and the bounded JSON part; do not silently broaden existing Company upload limits.
Implementation bounds are 32 KiB for the JSON part and 16 MiB for the complete
report request. Only the report-intake servlet receives the larger request limit.
Signature checks are format validation, not malware scanning or full parsing.

Generate evidence IDs server-side. Strip path components and unsafe control
characters from filenames, bound their length, and use a safe fallback name.
Download with attachment disposition, no-store, nosniff and an allowlisted content
type. Evidence must belong to the requested report; mismatches/missing bytes return
404. A missing attachment must not prevent report-detail retrieval; return
available=false and no download path. Never log complaint text or file contents.

## 5. Proposed additive persistence and consistency

Add a new Flyway migration following the backend naming convention; never edit
committed migrations. New `user_reports` contains generated Long id, reporter user
FK, target_type, target_id string, complaint, status, nullable processing_outcome,
and created_at. Enforce target/status allowlists and complaint bounds. Use a
polymorphic target reference because Candidate profile IDs are UUIDs while Company
and Job IDs are Longs. Validate target visibility on submission; retain references
and reports when targets later become unavailable. No target cascade deletion.

New `user_report_evidence` contains UUID id, report FK, safe filename, content type,
size, content hash, and private bytea content (nullable only to represent unavailable
retained evidence). Enforce valid media types, positive bounded size, and per-report
content-hash uniqueness. Index reports by created_at/id and filter columns; index
evidence by report_id. The application enforces attachment count and total limits.

Persist report, all evidence and a safe REPORT_SUBMITTED audit entry in one
transaction; audit failure rolls back submission. Audit contains actor, report ID
and target reference, without complaint/evidence. No external delivery side effect.
No delete or retention worker is introduced; platform retention policy remains a
separate requirement. This contract authorizes no historical data deletion.

Migration is additive: existing APIs/data remain unchanged. Deploy migration before
new report writers/readers. English backend messages are localized by clients;
timestamps are UTC instants rendered using vi-VN/Asia/Ho_Chi_Minh. Clients must render
complaint, outcome and filenames as plain text.

## 6. Errors

Preserve existing authentication/enforcement errors. Proposed scoped errors:

| HTTP | Identifier | Meaning |
|---|---|---|
| 400 | INVALID_REPORT_INPUT | Malformed fields, target identifier, filters, pagination or file format |
| 401 | Existing authentication identifier | Missing, invalid or revoked credentials |
| 403 | Existing authority/enforcement identifier | Wrong role/portal or restricted account |
| 404 | REPORT_TARGET_UNAVAILABLE | Submission target unavailable or nonexistent |
| 404 | REPORT_NOT_FOUND | Staff report missing |
| 404 | REPORT_EVIDENCE_NOT_FOUND | Missing/unavailable/wrong-report evidence |
| 413 | REPORT_CONTENT_TOO_LARGE | Scoped upload bounds exceeded |
| 500 | Existing internal error behavior | Mandatory persistence/audit failure; no success receipt |

Numeric identifiers, if required by surrounding conventions, will be selected after
checking the repository catalogue for collisions; the semantic identifiers above
are the proposed client contract. Availability failures are errors, not empty results.

## 7. Acceptance and verification

The maintainer accepted HTTP API tests backed by the existing PostgreSQL setup on
2026-11-01. This seam verifies HTTP auth/portal behavior, validation, response
privacy, persistence observed through Staff reads, evidence access and read-only
behavior. It does not prove browser UI, production mail, antivirus, external
providers, or performance under load. Use existing migration-test conventions for
schema constraints where HTTP observations cannot prove database integrity.

Verify valid submission becomes visible to Staff; every target type; unavailable
target privacy; anonymous/Staff submission refusal; ordinary User/Company-role
Staff-read refusal; ban enforcement; input/file limits; safe download headers;
wrong-report evidence refusal; status/type filtering and stable pagination; missing
report/evidence distinction; unchanged state on reads; no credential/contact leaks;
and complete rollback on validation or audit failure.

Work one failing HTTP behavior test then minimal implementation per TDD slice.
Run targeted files regularly, Java compilation as appropriate, the full backend
suite once at completion, and packaging for the migration/configuration change.
Perform the implement skill's final code review. Commit only with explicit user
authorization under root policy; do not push or open a PR without authorization.

## 8. Scope of recorded approval

The recorded approval covers this report-intake product extension, additive schema,
scoped API/privacy/validation/upload contracts and the existing HTTP/PostgreSQL
test seam. It does not approve dependencies, shared architecture changes, report
adjudication, or the upcoming sprint's MST/job-restoration work.
