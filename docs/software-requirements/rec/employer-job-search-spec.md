# Employer Job Search — Software Requirements Specification

| Field | Value |
| --- | --- |
| Specification ID | GW-SR-REC-JOB-SEARCH |
| Project | GuardWork |
| Module | Employer Job Publishing |
| Version | 1.0.0 |
| Last Updated | 2026-11-07 |
| Owner | Requesting maintainer |
| Approver | Pending named human approval |
| Status | Draft |

The requesting maintainer approved implementation of this contract in the task
conversation on 2026-11-07 ("Alright, $implement it") and confirmed the HTTP
integration test seam. A named approver was not supplied; lifecycle metadata remains
Draft for human completion rather than an agent marking it Approved.

## 1. Purpose and scope

Add keyword search to the existing private employer job list. This proposal extends
the GW-78 implementation contract's restriction that JSONB content remain opaque to
query filters. Its other access, pagination and lifecycle rules remain applicable.

## 2. API and functional requirements

`GET /api/companies/me/jobs?keyword=engineer&page=0&size=20`

| ID | Requirement |
| --- | --- |
| EJS-001 | Accept optional `keyword` alongside existing `status` and `needsReassignment` filters. Combine supplied filters with AND. |
| EJS-002 | Trim keyword whitespace; omitted or blank keywords preserve existing list behavior. Reject keywords longer than 200 characters after trimming with the existing job validation error response. |
| EJS-003 | Match a literal, case-insensitive, Vietnamese diacritic-insensitive substring of the title. Treat `%`, `_` and backslash as literal characters. |
| EJS-004 | Search the current DRAFT/PENDING_REVIEW revision title when one exists; otherwise search the Published Revision title. Do not search historical revisions. Missing titles do not match a nonblank keyword. |
| EJS-005 | Derive Company scope from the authenticated actor. Owners may search all Company jobs; Recruiters may search only jobs assigned to their current active membership period. Retain existing account, email, Company and membership checks; Company ADMIN gains no access. |
| EJS-006 | Apply matching and access filters before pagination and counting. Return each job once using the existing `ApiResponse<PageResponse<JobDetail>>` contract, ordered by `createdAt DESC, id DESC`. Retain page default 0, size default 20, size range 1–100 and safe offset checks. |
| EJS-007 | Preserve effective expiry filtering and existing status/reassignment semantics. Search performs no job, revision, assignment or audit mutations. |

## 3. Data and compatibility

Reuse existing PostgreSQL JSONB revisions and the installed `unaccent` extension.
No dependency, schema, migration, search index or new shared architecture is proposed.
The optional query parameter is additive; existing list clients retain their behavior.
Private job content must remain inaccessible through public search and other Companies.

## 4. Errors and verification

Retain existing authentication, authorization, validation and response envelopes.
No matches return HTTP 200 with an empty page and zero total elements.

Verify keyword normalization and bounds, Vietnamese/case matching, literal wildcard
characters, revision precedence and published fallback, combined filters, accurate
pagination/counts, Company isolation and current Recruiter assignment scope.
