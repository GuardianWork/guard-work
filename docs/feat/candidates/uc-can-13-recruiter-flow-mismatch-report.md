# UC-CAN-13 vs Recruiter Job Application Flow — Mismatch Report

## 1. Scope

This report compares:

- `UC-CAN-13` in `docs/feat/candidates/usecase.md`: Candidate tracks submitted Job Applications.
- The recruiter-submitted-application listing flow documented in `docs/software-requirements/rec/application-listing-spec.md` and `docs/backend/recruiter-application-listing.md`.
- The implemented flow before this change in `backend/src/main/java/com/guardwork/backend/application`.

## 2. Relevant implementation

| Area | Files |
|---|---|
| Candidate API | `application/controller/JobApplicationController.java` (`GET /api/candidate/applications`) |
| Candidate service | `application/service/JobApplicationService.java#list` |
| Recruiter API | `application/controller/RecruiterApplicationController.java` (`GET /api/companies/me/jobs/{jobId}/applications`) |
| Recruiter service | `application/service/JobApplicationService.java#listForCompany` |
| Repository | `application/repository/JobApplicationRepository.java` |
| Recruiter error handling | `application/controller/RecruiterApplicationErrorHandler.java` |
| Candidate error handling | `common/exception/GlobalExceptionalHandler.java` |
| Candidate submission specification | `can/application-submission-spec.md` |
| Recruiter listing specification | `rec/application-listing-spec.md` |

## 3. Mismatch inventory

| ID | Area | Source requirement / contract | Previous implementation | Impact | Resolution in this slice |
|---|---|---|---|---|---|
| MM-01 | Candidate filters/search | UC-CAN-13 requires filtering by status and searching by job/company. | `GET /api/candidate/applications` took no query parameters and returned the full list. | Candidate could not track applications by status or job/company. | Added `search`, `status`, `page`, and `size` validation and query support. |
| MM-02 | Candidate pagination | `REQ-CAN-APP-05` describes a paginated Candidate list; recruiter listing uses `PageResponse`. | Candidate endpoint returned `List<JobApplicationSummaryResponse>`. | No totals/page metadata; inconsistent with recruiter contract. | Candidate endpoint now returns `PageResponse<JobApplicationSummaryResponse>`. |
| MM-03 | Status lifecycle | UC-CAN-13 displays application statuses produced by the review workflow. The schema now permits `SUBMITTED`, `UNDER_REVIEW`, `SHORTLISTED`, `REJECTED`, and `WITHDRAWN`. | Only `SUBMITTED` is produced by submission. Review/reserved statuses were not produced by Candidate submission. | Candidate would only ever see `SUBMITTED`. | Candidate filter now accepts the workflow statuses; transitions remain recruiter-side work. |
| MM-04 | Candidate CV access | UC-CAN-13 requires displaying the attached CV. Snapshot stores an independent CV copy. | Candidate list exposed only `cvFileName`; there was no Candidate endpoint to download the submitted CV. | Candidate could not reopen the exact CV submitted. | Added `GET /api/candidate/applications/{applicationId}/cv/download-url`. |
| MM-05 | Error envelope | Recruiter invalid query values return `40001 INVALID_INPUT ...` through `ApiResponse`. | Candidate errors use `ErrorCode.INVALID_INPUT` (`40000`) through `ErrorApiResponse`. | Two different error shapes existed in the same module. | Candidate query errors use the existing Candidate `ErrorApiResponse` envelope. |
| MM-06 | Query type mismatch handling | Recruiter route has `RecruiterApplicationErrorHandler` for `MethodArgumentTypeMismatchException`. | Candidate controller had no comparable handler. | Adding pagination parameters risked non-standard parse errors. | Candidate filters use AppException-based validation and the global Candidate error handling. |
| MM-07 | Repository efficiency | Recruiter listing uses one native join projection. | Candidate `list()` called `snapshots.findById` per application. | N+1 query behavior and worse scaling. | Candidate listing now uses one native join projection. |
| MM-08 | Search normalization | Recruiter search supports trimming, 150 code points, accent-insensitive matching, and literal `%`/`_`. | Candidate had no search at all. | Naive search could introduce wildcard abuse or incorrect Vietnamese matching. | Reused recruiter normalization and escaping, matching job title and company name. |
| MM-09 | Post-suspension visibility | RAL-007 blocks recruiter listing for suspended jobs, even for Owner. | Candidate list now joins `job_postings` and excludes `staff_suspended` jobs from content and totals. | Suspended-job applications are no longer mixed with active-history applications. | Implemented per the approved mirror-recruiter policy for job-level suspension. Company-level suspension remains separate. |
| MM-10 | Logging privacy | Recruiter listing explicitly does not log names or search text. | Candidate listing had no explicit documentation or tests for query logging. | Future changes could retain personal search/job/company data. | Logging remains route-template based and query values are not logged. |
| MM-11 | Status default semantics | Candidate list had no filter semantics. | No distinction between all applications and submitted applications. | Filtering defaults could be ambiguous. | Omitted `status` means no filter. |
| MM-12 | Download DTO contract | Submitted CV had metadata but no download contract. | No Candidate-only application CV response existed. | Reusing `UploadUrlResponse` with null fields would be unclear. | Added `ApplicationCvDownloadResponse` with `url`, `expiresAt`, and `fileName`. |
| MM-13 | Status transition ownership | UC-CAN-13 and UC-CAN-14 imply status changes and Candidate notifications. | No transition or notification flow existed. | Status labels in UC-CAN-13 were ahead of implemented backend capability. | Transitions remain deferred to the approved pipeline slice. |
| MM-14 | Status vocabulary | UC-CAN-13 lists Submitted/Viewed/Rejected, while the current backend/schema use `SUBMITTED`, `UNDER_REVIEW`, `SHORTLISTED`, `REJECTED`, and `WITHDRAWN`. | Candidate list accepts the workflow statuses and no longer accepts legacy `VIEWED`. | Older use-case wording can suggest a status the backend rejects. | Workflow statuses remain canonical; update use-case wording in a follow-up. |

## 4. Resolved design decisions

1. Suspended-job visibility follows the approved mirror-recruiter policy: Candidate listing joins `job_postings` and excludes `staff_suspended` applications from content and totals.
2. Candidate query errors use `ErrorApiResponse`.
3. The Candidate filter uses the current workflow statuses `SUBMITTED`, `UNDER_REVIEW`, `SHORTLISTED`, `REJECTED`, and `WITHDRAWN`; legacy `VIEWED` is no longer accepted.
4. Downloaded submitted CVs return URL plus expiry metadata.
5. Download availability requires only active account state, not separately verified email.
6. Download remains available when the account suspension ends, if the snapshot still exists.
7. Retention/deletion policy governs whether the snapshot and download remain available.

## 5. Implemented backend changes

- `JobApplicationController` now accepts `search`, `status`, `page`, and `size`, returning `PageResponse<JobApplicationSummaryResponse>`.
- Added `GET /api/candidate/applications/{applicationId}/cv/download-url`.
- `JobApplicationService` validates pagination and exact status filters.
- Candidate search is trimmed, limited to 150 code points, escaped for wildcards, and accent-insensitive across job title and company name. Invalid Candidate search input now returns through the Candidate `ErrorApiResponse` envelope.
- Candidate listing uses one native join projection and stable `appliedAt DESC, id DESC` ordering.
- Submitted CV download verifies Candidate ownership and returns a short-lived signed URL plus expiry.
- No schema migration or dependency was added.

## 6. Verification

Targeted tests passed:

- `JobApplicationControllerTest`
- `JobApplicationHttpTest`
- `JobApplicationServiceTest`
- `JobApplicationMigrationTest`

Package build passed with Java 25 in this environment.

Candidate HTTP coverage added for listing filters, pagination, status validation, search, and submitted-CV download.

## 7. Remaining work

- Implement recruiter status transitions and Candidate notifications in the approved pipeline slice.
- Add further Candidate HTTP tests for suspended-job filtering and CV download edge cases.
- Update `can/application-submission-spec.md` contract if further Candidate-facing field or endpoint changes are approved.
