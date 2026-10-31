# Candidate Application Submission — Software Requirements Specification

| Field | Value |
|---|---|
| **Specification ID** | GW-SR-APP-01 |
| **Project** | GuardWork |
| **Module** | Candidate Applications |
| **File** | `application-submission-spec.md` |
| **Version** | 0.1.0 |
| **Date** | 2026-10-30 |
| **Last Updated** | 2026-10-30 |
| **Owner** | GuardWork thesis team |
| **Named human approver** | chiko |
| **Status** | Draft — implementation prepared; approval pending |
| **Related use case** | UC-CAN-11 — Điền thông tin ứng tuyển |

## 1. Purpose and scope

This specification defines the backend behavior for a Candidate submitting one application to
one published Job Posting. It covers:

- loading a prefill form from the Candidate's profile and CV library;
- accepting editable submitted contact details and an optional cover letter;
- attaching exactly one CV from the Candidate's own library;
- storing an immutable snapshot of the submitted application; and
- rejecting duplicate, unauthorized, unavailable or invalid submissions.

The change is intentionally limited to the `applications` and `application_snapshots` tables.
It does not add application status history, recruiter pipeline actions, interview/offer records,
Kafka, or an application outbox. Those remain separate work.

### 1.1 Decisions confirmed for this specification

| Decision | Value |
|---|---|
| CV attachment cardinality | Exactly one `cvId` per application. |
| Persistence scope | `applications` and `application_snapshots` only. |
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
      +--> VIEWED     (reserved for recruiter workflow; not implemented here)
      +--> REJECTED   (reserved for recruiter workflow; not implemented here)
```

Only `SUBMITTED` is produced by this change. `VIEWED` and `REJECTED` are accepted by the schema
so a later approved recruiter workflow can evolve without rewriting application identity.

### 2.2 Application snapshot

The snapshot is a point-in-time copy of what the Candidate submitted:

- submitted full name, email, phone number and cover letter;
- Job Posting title and Company name at submission time;
- CV file metadata; and
- an independent storage key for the copied CV.

The snapshot is immutable at the database boundary. It does not depend on the Candidate's mutable
profile, the Job Posting's later content changes, or the Candidate's CV library row.

### 2.3 CV snapshot ownership

The CV object is copied from the Candidate's library to an application-specific key:

```text
applications/{applicationId}/cv/{randomUuid}.{pdf|doc|docx}
```

Deleting the source CV from the Candidate's library must not delete or mutate the submitted copy.
`application_snapshots.source_cv_id` is historical traceability only and deliberately has no
foreign key to `candidate_cvs`.

## 3. Functional requirements

### 3.1 Form prefill

| ID | Requirement |
|---|---|
| APP-001 | The system **shall** derive the Candidate identity from the authenticated principal. A request payload **shall not** be able to select another Candidate. |
| APP-002 | The system **shall** reject the form request when the authenticated account is not `ACTIVE`. |
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
| APP-013 | The system **shall** validate the email as a non-blank value containing `@`, with a maximum length of 255. |
| APP-014 | The system **shall** require the referenced CV to belong to the authenticated Candidate. |
| APP-015 | The system **shall** verify the source CV object still exists and has an allowed PDF, DOC or DOCX format and a positive size no greater than 10 MB. |
| APP-016 | The system **shall** copy the CV to an independent application storage key before committing the application. |
| APP-017 | The system **shall** persist the Application and its Snapshot in one database transaction. |
| APP-018 | The system **shall** reject a second application for the same Candidate and Job Posting with `40903`, including when two requests race. |
| APP-019 | The system **shall** remove a copied CV object when the application transaction fails or a duplicate is detected after copying. Cleanup failure **shall not** mask the original error. |
| APP-020 | The system **shall** return `201 Created` with the new application identity and submitted summary on success. |
| APP-021 | The system **shall not** accept Candidate identity, Company identity, Job revision identity or status from the request payload. |
| APP-022 | The system **shall** write an application only when the Job Posting is `PUBLISHED` and the Company is `VERIFIED` and not banned. |

### 3.3 Concurrency and integrity

| ID | Requirement |
|---|---|
| APP-030 | The database **shall** enforce `UNIQUE(candidate_id, job_id)` on `applications`. |
| APP-031 | Submission **shall** lock the account, then the Company, then the Job Posting, matching the existing job lifecycle lock order. |
| APP-032 | Duplicate detection **shall** distinguish the `uk_applications_candidate_job` constraint from other integrity failures. |
| APP-033 | Application identity fields and Snapshot rows **shall** be protected against update and delete by database triggers. |
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
| `status` | VARCHAR(30) | NOT NULL, CHECK | `SUBMITTED`, `VIEWED`, `REJECTED`. |
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
| `source_cv_id` | UUID | Nullable, no FK | Historical source CV identity. |
| `cv_storage_key` | VARCHAR(255) | NOT NULL | Independent copy key. |
| `cv_storage_provider` | VARCHAR(30) | NOT NULL | `CLOUDINARY`. |
| `cv_file_name` | VARCHAR(255) | NOT NULL, non-blank | Original file name. |
| `cv_content_type` | VARCHAR(100) | NOT NULL, CHECK | PDF, DOC or DOCX. |
| `cv_file_size` | BIGINT | NOT NULL, > 0 | Verified bytes. |
| `snapshot_schema_version` | INTEGER | NOT NULL, default 1 | Future snapshot evolution. |
| `captured_at` | TIMESTAMPTZ | NOT NULL | Submission instant. |

The snapshot table rejects all updates and deletes. `source_cv_id` intentionally has no foreign
key so later CV deletion cannot fail or mutate the snapshot.

## 5. API specification

Base path: `/api/v1/candidate/applications`. All endpoints require an authenticated Candidate.

### 5.1 `GET /api/v1/candidate/applications/form?jobId={jobId}`

Returns the prefill form. `jobId` is required and must be a positive Job Posting identity.

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Success",
  "data": {
    "job": {
      "id": 42,
      "title": "Backend Engineer",
      "companyId": 7,
      "companyName": "Công ty Ánh Dương",
      "deadline": "2026-12-01T12:00:00Z"
    },
    "profile": {
      "fullName": "Nguyễn Văn An",
      "email": "an@example.vn",
      "phoneNumber": "0901234567"
    },
    "cvs": [
      {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "fileName": "CV.pdf",
        "contentType": "application/pdf",
        "fileSize": 204800,
        "isDefault": true
      }
    ],
    "defaultCvId": "550e8400-e29b-41d4-a716-446655440000",
    "missingProfileFields": [],
    "alreadyApplied": false
  }
}
```

### 5.2 `POST /api/v1/candidate/applications`

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
| `phoneNumber` | Required, exactly `0` plus nine digits. |
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
| BR-05 | A CV copy failure must leave no Application or Snapshot row. |
| BR-06 | An application may reference only an approved revision of a published Job Posting from a verified, non-banned Company. |
| BR-07 | Application status history, recruiter actions and event delivery are outside this specification. |

## 7. Error catalogue

| Error code | HTTP | Constant | Trigger |
|---|---|---|---|
| `40100` | 401 | `UNAUTHENTICATED` | Missing or invalid access token. |
| `40304` | 403 | `ACCOUNT_SUSPENDED` | Authenticated account is not `ACTIVE`. |
| `40405` | 404 | `CV_NOT_FOUND` | CV does not exist or belongs to another Candidate. |
| `40478` | 404 | `JOB_NOT_FOUND` | Job is missing, unpublished or belongs to a banned Company. |
| `40903` | 409 | `APPLICATION_ALREADY_SUBMITTED` | Candidate already applied to the Job Posting. |
| `40904` | 409 | `JOB_NOT_ACCEPTING_APPLICATIONS` | Job is paused, closed or expired. |
| `50301` | 503 | `APPLICATION_CV_COPY_FAILED` | Storage copy failed. |

## 8. Non-functional requirements

| ID | Category | Requirement |
|---|---|---|
| NFR-01 | Security | Candidate identity comes from `SecurityContext`, never from the payload. |
| NFR-02 | Integrity | Application and snapshot writes are atomic; storage cleanup is best-effort. |
| NFR-03 | Privacy | The snapshot retains only the submitted contact and CV metadata needed by the application workflow. |
| NFR-04 | Auditability | Application identity and snapshot rows are append-only at the database boundary. |
| NFR-05 | Compatibility | The migration is additive; no existing table or API is changed. |
| NFR-06 | Observability | Successful submissions log an event with application, job and Company identities; CV copy failures log a warning without CV contents. |

## 9. Open gaps and deferred work

| ID | Gap | Disposition |
|---|---|---|
| EC-CAN-APP-03 | No durable application event/outbox and no Kafka delivery. | Deferred by the approved scope; the application is still persisted atomically. |
| APP-GAP-01 | No recruiter list/detail, status transition or download endpoint. | Separate recruiter workflow. |
| APP-GAP-02 | No `application_status_events` history table. | Separate workflow; status values are reserved only. |
| APP-GAP-03 | No candidate application list endpoint (UC-CAN-13). | Separate candidate workflow. |
| APP-GAP-04 | No notification to Candidate or Recruiter (UC-CAN-14). | Depends on EC-CAN-APP-03 and notification workflow. |
| APP-GAP-05 | No frontend form. | No frontend exists in this repository. |

## 10. Verification

Implementation verification uses:

- `JobApplicationServiceTest` for prefill, validation, duplicate, unavailable-job, copy-failure and
  snapshot-mapping behavior;
- `JobApplicationControllerTest` for HTTP status, validation and error envelopes;
- `CandidateCvServiceTest` for ownership, copy, cleanup and missing-asset behavior;
- `JobApplicationHttpTest` for the authenticated end-to-end form/submit flow, duplicate rejection,
  foreign-CV rejection, suspended account, copy failure and unavailable job; and
- `JobApplicationMigrationTest` for PostgreSQL uniqueness, immutability, checks and snapshot
  independence.

Local verification uses `-Djava.version=21` because the current environment provides Java 21 while
the project declares Java 25. The production build remains Java 25.

## 11. Approval record

| Date | Actor | Decision |
|---|---|---|
| 2026-10-30 | User | Confirmed one CV, `applications` + `application_snapshots` only, and `chiko` as named approver. |
| Pending | chiko | Formal approval of this specification. |
