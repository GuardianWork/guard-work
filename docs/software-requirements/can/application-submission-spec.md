# Candidate Application Submission — Software Requirements Specification

| Field | Value |
|---|---|
| **Specification ID** | GW-SR-APP-01 |
| **Project** | GuardWork |
| **Module** | Candidate Applications |
| **File** | `application-submission-spec.md` |
| **Version** | 0.3.0 |
| **Date** | 2026-10-30 |
| **Last Updated** | 2026-11-18 |
| **Owner** | GuardWork thesis team |
| **Named human approver** | chiko |
| **Status** | Draft — implementation prepared; candidate application-list fields approved 2026-11-05; CV access-grant amendment approved 2026-11-17; inactivity/reactivation/purge amendment approved 2026-11-18; full approval pending |
| **Related use case** | UC-CAN-11 — Điền thông tin ứng tuyển |
| **Related specification** | GW-SR-CV-02 (`candidate-cv-access-retention-spec.md`) |

## 1. Purpose and scope

This specification defines the backend behavior for a Candidate submitting one application to
one published Job Posting. It covers:

- loading a prefill form from the Candidate's profile and CV library;
- accepting editable submitted contact details and an optional cover letter;
- attaching exactly one CV from the Candidate's own library;
- storing an immutable snapshot of the submitted application;
- recording one access grant that authorizes the receiving Company to read that library CV; and
- rejecting duplicate, unauthorized, unavailable or invalid submissions.

The change is intentionally limited to the `applications`, `application_snapshots` and
`candidate_cv_access_grants` tables. It does not add application status history, recruiter pipeline
actions, interview/offer records, Kafka, or an application outbox. Those remain separate work.

### 1.1 Decisions confirmed for this specification

| Decision | Value |
|---|---|
| CV attachment cardinality | Exactly one `cvId` per application. |
| Persistence scope | `applications` and `application_snapshots`; the submission transaction also writes one `candidate_cv_access_grants` row. |
| Named human approver | `chiko`. |
| Duplicate rule | At most one application per Candidate and Job Posting. |
| Event delivery | Deferred. `EC-CAN-APP-03` remains open. |

## 2. Domain model and lifecycle

### 2.1 Application

An Application is the stable identity of one Candidate's submission to one Job Posting.
It records the Candidate, Job Posting, Company, submitted revision and submission time.
Identity fields are immutable after insert.

```text
(no application)
      |
      | submit
      v
  SUBMITTED
      |
      +--> active stages under GW-SR-APPLICATION-REVIEW
      +--> terminal REJECTED or WITHDRAWN
```

Only `SUBMITTED` is produced by this submission specification. The accepted Application status set is
`SUBMITTED`, `UNDER_REVIEW`, `SHORTLISTED`, `INTERVIEWING`, `HIRED`, `REJECTED`, `WITHDRAWN`, and
`INACTIVE`. Lifecycle transitions are owned by GW-SR-APPLICATION-REVIEW and do not alter the
immutable Application identity or Snapshot rows. GW-SR-CV-02 owns the inactivity clock, the
reversible `INACTIVE` reactivation, and rejected legacy-copy purge.

### 2.2 Application snapshot

The snapshot is a point-in-time record of what the Candidate submitted:

- submitted full name, email, phone number and cover letter;
- Job Posting title and Company name at submission time;
- CV file metadata; and
- the storage reference and storage mode of the CV.

The snapshot is immutable at the database boundary except for the one-way `cv_purged_at` marker. It
does not depend on the Candidate's mutable profile, the Job Posting's later content changes, or the
Candidate's CV library row.

### 2.3 CV access ownership

New Applications share the Candidate's library CV object. The snapshot records
`cv_storage_mode = 'SHARED'` and `cv_storage_key` equal to the library `public_id`; the key is
descriptive only and never authorizes a Company read. Submission records one open-ended
`candidate_cv_access_grants` row for the receiving Company in the same transaction:

```text
candidate_cv_access_grants
  application_id -> the submitted Application
  cv_id          -> the shared library CV
  company_id     -> the receiving Company
  storage_key    -> the library public_id
```

Only a live grant authorizes the Company to read the CV. Deleting the source CV from the
Candidate's library ends the Candidate's own access immediately and starts a bounded review window
for the Company when the Application is in an active review stage; a `HIRED` Application loses
access immediately. GW-SR-CV-02 owns the grant lifecycle, inactivity, revocation, retention window,
and purge.

Applications submitted before this change keep `cv_storage_mode = 'LEGACY_COPY'` and their
application-specific copied object. They are not backfilled with grants and remain readable through
the historical copy. `application_snapshots.source_cv_id` is historical traceability only and
deliberately has no foreign key to `candidate_cvs`.

## 3. Functional requirements

### 3.1 Form prefill (frontend-assembled; no backend endpoint)

| ID | Requirement |
|---|---|
| APP-001 | The system **shall** derive the Candidate identity from the authenticated principal. A request payload **shall not** be able to select another Candidate. |
| APP-002 | Prefill is frontend-only. The backend rejects submission when the authenticated account cannot be settled as `ACTIVE`; in the normal JWT flow this is reported as `40100`. |
| APP-003 | The system **shall** return the current profile full name, account email and phone number as prefill values. |
| APP-004 | The system **shall** return the Candidate's CV options and identify the default CV when one exists. |
| APP-005 | The system **shall** report profile fields that are missing or blank so the client can ask the Candidate to complete them. |
| APP-006 | The system **shall** report whether the Candidate has already applied to the Job Posting. |
| APP-007 | The system **shall** return only a published, currently available Job Posting. Missing, unpublished, banned-Company or expired jobs **shall** be rejected as not found or unavailable according to section 7. |

### 3.2 Submission

| ID | Requirement |
|---|---|
| APP-010 | The system **shall** require `jobId`, `fullName`, `email`, `phoneNumber` and `cvId`. `coverLetter` is optional. |
| APP-011 | The system **shall** trim submitted text before validation and snapshotting. A blank cover letter **shall** be stored as `null`. |
| APP-012 | The system **shall** validate the phone number as exactly ten digits beginning with `0`. |
| APP-013 | The system **shall** validate the email as a non-blank value in standard email format containing `@`, with a maximum length of 255. |
| APP-014 | The system **shall** require the referenced CV to belong to the authenticated Candidate. |
| APP-015 | The system **shall** verify the source CV object still exists and has an allowed PDF, DOC or DOCX format and a positive size no greater than 10 MB. |
| APP-016 | The system **shall** share the Candidate's library CV object and record one open-ended access grant for the receiving Company in the submission transaction. It **shall not** copy the CV bytes. |
| APP-017 | The system **shall** persist the Application, its Snapshot, the access grant and the initial status history in one database transaction. |
| APP-018 | The system **shall** reject a second application for the same Candidate and Job Posting with `40903`, including when two requests race. |
| APP-019 | The system **shall** leave no Application, Snapshot, or access grant when submission fails. A missing library object **shall** fail with `40405`; storage cleanup of incomplete registrations remains best-effort and **shall not** mask the original error. |
| APP-020 | The system **shall** return `201 Created` with the new application identity and submitted summary on success. |
| APP-021 | The system **shall not** accept Candidate identity, Company identity, Job revision identity or status from the request payload. |
| APP-022 | The system **shall** write an application only when the Job Posting is `PUBLISHED` and the Company is `VERIFIED` and not banned. |

### 3.3 Concurrency and integrity

| ID | Requirement |
|---|---|
| APP-030 | The database **shall** enforce `UNIQUE(candidate_id, job_id)` on `applications`. |
| APP-031 | Submission **shall** lock the account, then the Company, then the Job Posting, matching the existing job lifecycle lock order. |
| APP-032 | Duplicate detection **shall** distinguish the `uk_applications_candidate_job` constraint from other integrity failures. |
| APP-033 | Application identity fields and Snapshot rows **shall** be protected against update and delete by database triggers, except the one-way `cv_purged_at` marker used by the rejected-legacy-copy purge. |
| APP-034 | A Candidate profile that has applications **shall not** be deleted silently while those applications exist. |

## 4. Data model

### 4.1 `applications`

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | UUID | PK | Assigned by the service. |
| `candidate_id` | UUID | NOT NULL, FK → `candidate_profiles(id)` | Authenticated Candidate. |
| `job_id` | BIGINT | NOT NULL, composite FK | Job Posting identity. |
| `company_id` | BIGINT | NOT NULL, composite FK | Company identity at submission. |
| `submitted_revision_id` | BIGINT | NOT NULL, composite FK | Approved revision submitted against. |
| `status` | VARCHAR(30) | NOT NULL, CHECK | `SUBMITTED`, `UNDER_REVIEW`, `SHORTLISTED`, `INTERVIEWING`, `HIRED`, `REJECTED`, `WITHDRAWN`, `INACTIVE`. |
| `status_changed_at` | TIMESTAMPTZ | NOT NULL, default `now()` | Last status transition; backfilled from status history. |
| `version` | BIGINT | NOT NULL, default 0 | Optimistic version. |
| `applied_at` | TIMESTAMPTZ | NOT NULL | Submission instant. |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last lifecycle update. |

Constraints:

- `UNIQUE(candidate_id, job_id)` named `uk_applications_candidate_job`;
- `FOREIGN KEY(job_id, company_id) → job_postings(id, company_id)`;
- `FOREIGN KEY(submitted_revision_id, job_id) → job_posting_revisions(id, job_id)`.

Indexes: `(candidate_id, applied_at DESC)`, `(job_id, applied_at DESC)`,
`(company_id, applied_at DESC)`.

### 4.2 `application_snapshots`

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `application_id` | UUID | PK, FK → `applications(id)` | One snapshot per application. |
| `full_name` | VARCHAR(150) | NOT NULL, non-blank | Submitted value. |
| `email` | VARCHAR(255) | NOT NULL, contains `@` | Submitted value. |
| `phone_number` | VARCHAR(10) | NOT NULL, `^0[0-9]{9}$` | Submitted value. |
| `cover_letter` | VARCHAR(1000) | Nullable | Trimmed; blank becomes null. |
| `job_title` | VARCHAR(200) | NOT NULL, non-blank | Title at submission. |
| `company_name` | VARCHAR(255) | NOT NULL, non-blank | Company name at submission. |
| `job_location` | VARCHAR(1000) | Nullable | Published job location captured at submission time; may be null for older snapshots. |
| `work_mode` | VARCHAR(30) | Nullable | Published work arrangement captured at submission time; may be null for older snapshots. |
| `job_deadline` | TIMESTAMPTZ | Nullable | Published job deadline captured at submission time; may be null for older snapshots. |
| `source_cv_id` | UUID | Nullable, no FK | Historical source CV identity. |
| `cv_storage_key` | VARCHAR(255) | NOT NULL | `SHARED`: library `public_id`; `LEGACY_COPY`: application copy key. |
| `cv_storage_mode` | VARCHAR(20) | NOT NULL, default `LEGACY_COPY`, CHECK | `SHARED` or `LEGACY_COPY`. |
| `cv_storage_provider` | VARCHAR(30) | NOT NULL | `CLOUDINARY`. |
| `cv_file_name` | VARCHAR(255) | NOT NULL, non-blank | Original file name. |
| `cv_content_type` | VARCHAR(100) | NOT NULL, CHECK | PDF, DOC or DOCX. |
| `cv_file_size` | BIGINT | NOT NULL, > 0 | Verified bytes. |
| `cv_purged_at` | TIMESTAMPTZ | Nullable | Set when a rejected legacy copy's bytes were deleted; metadata is retained. |
| `snapshot_schema_version` | INTEGER | NOT NULL, default 1 | Future snapshot evolution. |
| `captured_at` | TIMESTAMPTZ | NOT NULL | Submission instant. |

The snapshot table rejects all updates and deletes except the one-way `cv_purged_at` marker, which
records deletion of a rejected legacy copy while retaining the metadata. `source_cv_id`
intentionally has no foreign key so later CV deletion cannot fail or mutate the snapshot.

`cv_storage_mode` distinguishes the two storage contracts. Existing and pre-amendment rows default
to `LEGACY_COPY`. New submissions write `SHARED` and must never fall back to `cv_storage_key` for a
Company read; only a live `candidate_cv_access_grants` row authorizes that read.

GW-103 added the nullable submission context columns `job_location`, `work_mode`, and `job_deadline`
(migration `V2026.11.04.102`); they remain nullable so pre-GW-103 snapshots stay readable. `work_mode`
carries the published job arrangement (`ONSITE`, `HYBRID`, `REMOTE`).

## 5. API specification

Base path: `/api/candidate/applications`. All endpoints require an authenticated Candidate.

### 5.1 Prefill (frontend-only)

There is no backend form endpoint. The client assembles prefill values from the existing
candidate profile and CV endpoints, normalizes phone numbers to `0xxxxxxxxx` when needed, and
submits them through `POST /api/candidate/applications`.

- `APP-GAP-06`: Backend no longer exposes a dedicated application form prefill endpoint.

### 5.1 Candidate application list

#### `GET /api/candidate/applications`

Returns the authenticated Candidate's submitted applications, ordered by `appliedAt DESC`, then Application ID DESC.

Query parameters:

| Parameter | Default | Contract |
|---|---:|---|
| `search` | None | Optional partial match against submitted Job title or Company name from the immutable Application Snapshot. Matching ignores case and Vietnamese accents. Surrounding whitespace is trimmed; blank input means no filter. Up to 150 Unicode code points are accepted after trimming. `%` and `_` are treated as literal characters. |
| `status` | None | Optional exact status filter. Accepted values are `SUBMITTED`, `UNDER_REVIEW`, `SHORTLISTED`, `INTERVIEWING`, `HIRED`, `REJECTED`, `WITHDRAWN`, and `INACTIVE`; any other supplied value returns `400`. |
| `page` | `0` | Zero-based page number. Must be nonnegative and must not produce a pagination offset above `Integer.MAX_VALUE`. |
| `size` | `20` | Page size from `1` through `100`. |

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Applications retrieved successfully",
  "data": {
    "content": [
      {
        "id": "0f5f0a36-5d1f-4a5d-9f49-6e4a8f0c7b11",
        "jobId": 42,
        "jobTitle": "Backend Engineer",
        "companyName": "Công ty Ánh Dương",
        "location": "Hà Nội",
        "workMode": "REMOTE",
        "deadline": "2026-11-30T23:59:59Z",
        "status": "SUBMITTED",
        "appliedAt": "2026-10-30T09:00:00Z",
        "cvFileName": "CV.pdf",
        "version": 0,
        "candidateFeedback": null
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

An empty result or a page beyond the last result returns `200` with an empty `content` array and the accurate matching total.

Applications attached to suspended Job Postings (`staff_suspended = true`) are excluded from both `content` and `totalElements`.

#### `GET /api/candidate/applications/{applicationId}/cv/download-url`

Returns a short-lived signed download URL for the submitted CV owned by the authenticated Candidate.

- `SHARED`: the URL is issued only while the source library CV is active. After the Candidate
  deletes that CV, the endpoint returns `404` (`CV_NOT_FOUND`).
- `LEGACY_COPY`: the URL points at the historical application copy.
- URL lifetime is 15 minutes.

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Success",
  "data": {
    "url": "https://...",
    "expiresAt": "2026-11-03T12:15:00Z",
    "fileName": "CV.pdf"
  }
}
```

### 5.2 Company CV access

Company-side CV reads are scoped to GW-SR-CV-02. The same submitted Application exposes:

- `GET /api/companies/me/jobs/{jobId}/applications/{applicationId}/cv` for the inline PDF view; and
- `GET /api/companies/me/jobs/{jobId}/applications/{applicationId}/cv/download-url` for a
  five-minute signed URL covering PDF, DOC, and DOCX.

A `SHARED` Application requires a live access grant for the authenticated Company. Expired,
revoked, or other-Company grants return the concealed `404` (`JOB_NOT_FOUND`). A `LEGACY_COPY`
Application reads its historical copy under the existing Application access rules.

### 5.3 `POST /api/candidate/applications`

Submits the application.

**Request body**

```json
{
  "jobId": 42,
  "fullName": "Nguyễn Văn An",
  "email": "an@example.vn",
  "phoneNumber": "0901234567",
  "coverLetter": "Em quan tâm đến vị trí này.",
  "cvId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Validation**

| Field | Rule |
|---|---|
| `jobId` | Required, positive. |
| `fullName` | Required, non-blank, max 150. |
| `email` | Required, valid email format, max 255. |
| `phoneNumber` | Required; accepts `0xxxxxxxxx` or `+84xxxxxxxxx` and is normalized to `0xxxxxxxxx`. |
| `coverLetter` | Optional, max 1,000 after trim. |
| `cvId` | Required, owned by the authenticated Candidate. |

**Response `201 Created`**

```json
{
  "status": 201,
  "message": "Application submitted successfully",
  "data": {
    "id": "0f5f0a36-5d1f-4a5d-9f49-6e4a8f0c7b11",
    "jobId": 42,
    "jobTitle": "Backend Engineer",
    "companyId": 7,
    "companyName": "Công ty Ánh Dương",
    "location": "Hà Nội",
    "workMode": "REMOTE",
    "deadline": "2026-11-30T23:59:59Z",
    "status": "SUBMITTED",
    "appliedAt": "2026-10-30T09:00:00Z",
    "cvFileName": "CV.pdf"
  }
}
```

## 6. Business rules

| ID | Rule |
|---|---|
| BR-01 | One Candidate may submit at most one application per Job Posting. |
| BR-02 | The authenticated principal is the only source of Candidate identity. |
| BR-03 | A submitted application is a historical fact; its identity and snapshot do not change when the Candidate edits their profile or CV, or when the Company changes its name. |
| BR-04 | A CV must be owned by the Candidate and must still exist in storage at submission time. |
| BR-05 | A failed submission must leave no Application, Snapshot, or access grant. |
| BR-06 | An application may reference only an approved revision of a published Job Posting from a verified, non-banned Company. |
| BR-07 | Application status history, recruiter actions and event delivery are outside this specification. |

## 7. Error catalogue

| Error code | HTTP | Constant | Trigger |
|---|---|---|---|
| `40100` | 401 | `UNAUTHENTICATED` | Missing/invalid access token; suspended accounts are normally rejected here by JWT enforcement. |
| `40021` | 400 | `INVALID_CV_FILE_FORMAT` | CV asset has an invalid or unverified PDF, DOC or DOCX format or size. |
| `40405` | 404 | `CV_NOT_FOUND` | CV does not exist or belongs to another Candidate. |
| `40478` | 404 | `JOB_NOT_FOUND` | Job is missing, unpublished or belongs to a banned Company. |
| `40903` | 409 | `APPLICATION_ALREADY_SUBMITTED` | Candidate already applied to the Job Posting. |
| `40904` | 409 | `JOB_NOT_ACCEPTING_APPLICATIONS` | Job is paused, closed or expired. |

`APPLICATION_CV_COPY_FAILED` (`50301`) is no longer triggered because submission does not copy the
CV. The constant remains in the error enum for client compatibility.

## 8. Non-functional requirements

| ID | Category | Requirement |
|---|---|---|
| NFR-01 | Security | Candidate identity comes from `SecurityContext`, never from the payload. |
| NFR-02 | Integrity | Application, snapshot, access grant and initial status history writes are atomic; storage cleanup is best-effort. |
| NFR-03 | Privacy | The snapshot retains only the submitted contact and CV metadata needed by the application workflow. Company CV reads are limited to a live grant for that Application and Company. |
| NFR-04 | Auditability | Application identity, snapshot rows and access-grant identity are append-only at the database boundary. |
| NFR-05 | Compatibility | The migration is additive except for the default-CV index replacement and the snapshot column default; no existing endpoint path or response field is removed. |
| NFR-06 | Observability | Successful submissions log an event with application, job and Company identities; a missing or invalid CV logs a warning without CV contents, storage keys, or signed URLs. |

## 9. Open gaps and deferred work

| ID | Gap | Disposition |
|---|---|---|
| EC-CAN-APP-03 | No durable application event/outbox and no Kafka delivery. | Deferred by the approved scope; the application is still persisted atomically. |
| APP-GAP-01 | Company-side list, detail, status transitions, and withdrawal are scoped to GW-SR-APPLICATION-LISTING, GW-SR-APPLICATION-DETAILS, and GW-SR-APPLICATION-REVIEW. CV access grants and retention are scoped to GW-SR-CV-02. This submission specification remains Candidate-side and snapshot focused. | Separate recruiter workflows; the shared CV contract is defined by GW-SR-CV-02. |
| APP-GAP-02 | `application_status_history` is owned by GW-SR-APPLICATION-REVIEW, not this submission specification. | Status values remain part of the Application lifecycle; this submission specification supplies only the initial `SUBMITTED` record. |
| APP-GAP-03 | Candidate application list endpoint supports read-only tracking with status filtering (including `INACTIVE`), job/company search, pagination, submitted-CV download, and exclusion of suspended-Job-Posting applications. Full status transitions remain separate recruiter pipeline work. | Implemented for UC-CAN-13 listing scope; inactivity/reactivation is owned by GW-SR-CV-02. |
| APP-GAP-04 | No notification to Candidate or Recruiter (UC-CAN-14). | Depends on EC-CAN-APP-03 and notification workflow. |
| APP-GAP-05 | No frontend form. | No frontend exists in this repository. |
| APP-GAP-06 | No backend form prefill endpoint. | Prefill is assembled by the frontend from profile and CV endpoints. |

## 10. Verification

Implementation verification uses:

- `JobApplicationServiceTest` for prefill, validation, duplicate, unavailable-job, missing-library
  object, grant creation, shared and legacy CV resolution, and snapshot-mapping behavior;
- `JobApplicationControllerTest` for HTTP status, validation and error envelopes;
- `CandidateCvServiceTest` for ownership, shared reference, authenticated-only registration, soft
  delete, review window, retention and missing-asset behavior;
- `CandidateCvRetentionServiceTest` for deferred object purge and retry;
- `JobApplicationHttpTest` for the authenticated end-to-end form/submit flow, duplicate rejection,
  foreign-CV rejection, suspended account, missing library object, Candidate access loss after CV
  deletion, Company inline read, Company download URL, rejection revocation, legacy Applications,
  `INACTIVE` concealment/reactivation, company-only inactivity notifications, rejected-copy purge,
  and withdrawal from `INACTIVE`;
- `ApplicationInactivityServiceTest` for the inactivity boundary, revocation, and notification
  recipients;
- `ApplicationCvPurgeServiceTest` for rejected legacy-copy deletion and retry;
- `JobApplicationMigrationTest` for PostgreSQL uniqueness, immutability, grant constraints and
  triggers, snapshot mode, default-CV tombstone behavior, live-grant and purge queries, and
  snapshot independence; and
- `CloudinaryCandidateCvStorageTest` for signed `access_mode` and provider failure mapping.

Local verification uses `-Djava.version=21` because the current environment provides Java 21 while
the project declares Java 25. The production build remains Java 25.

## 11. Approval record

| Date | Actor | Decision |
|---|---|---|
| 2026-10-30 | User | Confirmed one CV, `applications` + `application_snapshots` only, and `chiko` as named approver. |
| Pending | chiko | Formal approval of this specification. |
| 2026-11-01 | Requesting human user | Approved section 12 (APP-023–APP-025), including Recruiter, Admin and Owner roles. |
| 2026-11-05 | Requesting human user | Approved the Candidate application list response fields added by GW-103: `location`, `workMode`, `deadline`, `version`, and `candidateFeedback`. |
| 2026-11-17 | Requesting human user | Approved the CV access amendment in section 13: no per-application copy, grant-based Company access, one-month review window after Candidate deletion, and deferred purge. |
| 2026-11-18 | Requesting human user | Approved section 13.1: `INACTIVE` after 30 days without a status update, reversible reactivation with grant epochs, active-stage-only deletion window, and rejection purge of legacy copies with metadata retained. |

## 12. Scoped amendment: current-company member applications

Status: Approved by the requesting human user on 2026-11-01 for APP-023–APP-025 only.
The approval status of the remainder of this specification is unchanged.

The approved restriction is scoped to submission eligibility:

| ID | Approved requirement |
|---|---|
| APP-023 | Submission shall reject an authenticated User whose current persisted Company Membership is `ACTIVE`, has Company Role `RECRUITER`, `ADMIN`, or `OWNER`, and belongs to the Job Posting's Company. Return HTTP `403` using the existing `40300` (`FORBIDDEN`) error envelope. |
| APP-024 | An active Company Member may apply to another Company's available Job Posting. A historical `LEFT` or `REMOVED` membership shall not trigger APP-023. Existing eligibility rules still apply. |
| APP-025 | Check membership within the submission transaction after locking the account and resolving the target Company and Job Posting, before locking the CV reference or writing an Application, Snapshot, or access grant. Use persisted membership rather than token or client role claims. |

This amendment does not change existing applications, add a dependency, or require a schema
migration. Request and success-response shapes remain unchanged. Clients must handle the existing
forbidden response for this new submission condition.

Verification shall cover rejection for each active Company Role (`RECRUITER`, `ADMIN`, and
`OWNER`) of the target Company without CV or application writes, and successful submission
for another Company, no active membership, and historical `LEFT`/`REMOVED` membership.

## 13. Scoped amendment: grant-based CV access

Status: Approved by the requesting human user on 2026-11-17 for the snapshot and CV access
requirements. The approval status of the remainder of this specification is unchanged.

The prior contract copied the Candidate's library CV into an application-specific object before
committing the application. That copied every CV once per submission and left every application with
an independent storage object. The approved replacement is:

| ID | Approved requirement |
|---|---|
| APP-016 (replacement) | Share the Candidate's library CV object. Record one open-ended access grant for the receiving Company in the submission transaction. No CV bytes are copied. |
| APP-019 (replacement) | A submission failure leaves no Application, Snapshot, or access grant row. A missing library object fails with `40405`. |
| APP-017 (clarified) | The submission transaction persists the Application, Snapshot, access grant and initial status history atomically. |

Supporting behavior is normatively defined in GW-SR-CV-02: grant creation, identity immutability,
revocation on `REJECTED`/`WITHDRAWN`/`INACTIVE`, the one-month Company review window after Candidate
deletion for active stages, automatic object purge, and the `LEGACY_COPY` compatibility path.

This amendment:

- replaces the copy step with a shared library reference plus an access grant;
- makes the snapshot `cv_storage_mode = 'SHARED'` for new Applications;
- preserves every existing Application as `LEGACY_COPY` with no grant backfill;
- ends the Candidate's own submitted-CV access when they delete the library CV;
- keeps the Company's access for the configured review window (default 30 days) while the
  Application is in an active stage, then removes it and purges the bytes automatically;
- requires new uploads to use authenticated Cloudinary delivery; and
- leaves no existing endpoint path, response field, dependency, or outbox behavior changed.

### 13.1 Amendment: inactivity, reactivation, and rejection purge (2026-11-18)

Status: Approved by the requesting human user on 2026-11-18. GW-SR-CV-02 remains the normative
owner of these rules.

| ID | Approved requirement |
|---|---|
| APP-026 | An active Application with no status transition for the configured inactivity period (default 30 days) becomes `INACTIVE`. `HIRED`, `REJECTED`, and `WITHDRAWN` are excluded. |
| APP-027 | `INACTIVE` is reversible only through `UNDER_REVIEW` by an eligible Owner or currently assigned Recruiter. Reactivation starts a new CV access-grant epoch only while the shared library CV still exists. |
| APP-028 | On `REJECTED`, the application-specific `LEGACY_COPY` object is deleted; the snapshot row and metadata remain. `SHARED` library objects are never deleted by this path. Already-rejected legacy copies are backfilled by the same worker. |
| APP-029 | `INACTIVE` Applications conceal Company CV access while company detail remains visible. The Candidate can withdraw from `INACTIVE`. |
| APP-030 | Only the Company owner and currently assigned Recruiters receive the `APPLICATION_INACTIVE` notification. The Candidate sees the status but receives no notification. |

`status_changed_at` is the inactivity clock and is backfilled from append-only status history. A
grant may have multiple epochs, but at most one non-revoked epoch per Application. The post-deletion
review window applies only to active stages; `HIRED` ends Company access immediately when the
Candidate deletes the CV. The legal retention-agreement exception is deferred; rejection purges the
legacy copy unconditionally in this scope.
