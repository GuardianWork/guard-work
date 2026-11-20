# Candidate CV Access Grants and Library Retention — Software Requirements Specification

| Field | Value |
|---|---|
| **Specification ID** | GW-SR-CV-02 |
| **Project** | GuardWork |
| **Module** | Candidate CV Library and Application CV Access |
| **File** | `candidate-cv-access-retention-spec.md` |
| **Version** | 0.3.0 |
| **Date** | 2026-11-17 |
| **Last Updated** | 2026-11-20 |
| **Owner** | GuardWork thesis team |
| **Named human approver** | chiko |
| **Status** | Draft — implementation prepared; inactivity, reactivation, rejection-purge and binary Company download amendments approved by the requesting human user 2026-11-18 and 2026-11-20, formal approval pending |
| **Related use cases** | UC-CAN-05 — Xóa hồ sơ xin việc; UC-CAN-11 — Điền thông tin ứng tuyển; EC-CAN-CV-04 |
| **Related specifications** | GW-SR-CAND-02 (`candidate-cv-upload-spec.md`), GW-SR-APP-01 (`application-submission-spec.md`) |
| **Access-control matrix** | [`../backend/candidate-cv-access-control-matrix.md`](../backend/candidate-cv-access-control-matrix.md) (Vietnamese implementation reference) |

## 1. Purpose and scope

This specification defines how a Candidate CV library object is shared with a receiving Company
instead of being copied for every application. It covers:

- the access grant that authorizes one Company to read one Candidate CV for one Application;
- the Candidate CV library lifecycle (`deleted_at` tombstone and deferred object purge);
- the bounded review window for active stages that survives Candidate CV deletion;
- the retention worker that removes the shared object once no live grant remains;
- compatibility with Applications submitted before this change (`LEGACY_COPY`); and
- the Candidate and Company endpoints that issue CV access; and
- the inactivity lifecycle (`INACTIVE`) and its grant-epoch reactivation; and
- deletion of a rejected legacy application CV copy while retaining snapshot metadata.

The requirement is:

> Access to a CV link is restricted to the owning Candidate and the Company receiving that
> application. The Candidate and the receiving Company are the only principals that may read the
> CV. Deleting the library CV ends the Candidate's own access immediately; the Company keeps a
> one-month review window only while the Application is in an active stage, and a `HIRED`
> Application loses access immediately. A Company that does not update the status for one month
> loses access through `INACTIVE`. Once no permission remains, the stored bytes are removed
> automatically.

### 1.1 Out of scope

- Candidate library upload validation, quota, default CV selection, and listing rules, which remain
  in GW-SR-CAND-02 and UC-CAN-03/UC-CAN-06.
- Application submission field validation, duplicate detection, and eligibility rules, which remain
  in GW-SR-APP-01.
- Recruiter listing, detail redaction, status transitions, notifications, and audit conventions,
  which remain in the Application Review and Recruiter Application listing specifications.
- Account deletion, staff-initiated CV removal, legal hold, and data-subject export.
- Migration of pre-existing Cloudinary objects from `public` to `authenticated` access mode.

## 2. Definitions

| Term | Meaning |
|---|---|
| **Library object** | The one Cloudinary object under `candidates/{candidateId}/cvs/{uuid}` that holds the CV bytes. |
| **Shared snapshot** | An `application_snapshots` row with `cv_storage_mode = 'SHARED'`. Its `cv_storage_key` points at the library object and is descriptive only; it never authorizes a Company read. |
| **Legacy copy** | An `application_snapshots` row with `cv_storage_mode = 'LEGACY_COPY'`. Its `cv_storage_key` points at an application-specific copy created before this change. |
| **Access grant** | One `candidate_cv_access_grants` row that authorizes one Company to read one library object for one Application. |
| **Grant epoch** | One grant generation. An Application may have several epochs across its life, but at most one non-revoked epoch at a time. Reactivation from `INACTIVE` starts the next epoch. |
| **Live grant** | A grant with `revoked_at IS NULL` and (`expires_at IS NULL` or `expires_at > now()`). |
| **Tombstone** | A `candidate_cvs` row with `deleted_at` set. It is hidden from the Candidate library but retained so grants keep a valid foreign key and purge retries stay idempotent. |
| **Review window** | The interval after Candidate deletion during which a Company keeps reading the CV: `deleted_at + access-grant TTL` (default 30 days). It applies only while the Application is in an active review stage. |
| **Inactivity clock** | `applications.status_changed_at`, the last status transition. A Company that does not change the status for the configured period causes the Application to become `INACTIVE`. |
| **INACTIVE** | A reversible Application status. CV access is denied while inactive; reactivation must pass through `UNDER_REVIEW` and starts a new grant epoch only if the shared library CV still exists. |
| **Purge** | Deletion of the Cloudinary library object after no live grant remains, recorded by `storage_deleted_at`. |
| **Rejected-copy purge** | Deletion of a `LEGACY_COPY` application object after `REJECTED`, recorded by `application_snapshots.cv_purged_at`. Snapshot metadata is retained. |

## 3. Functional requirements

### 3.1 Grant-based access

| ID | Requirement |
|---|---|
| CVACL-001 | The system **shall** authorize a Company CV read from an explicit access grant. A snapshot storage key, a Candidate CV id, or an Application id alone **shall not** authorize access. |
| CVACL-002 | One Application **shall** have at most one **non-revoked** access grant at a time. Historical revoked epochs **shall** remain append-only. |
| CVACL-003 | A grant **shall** bind the Application, epoch, Candidate CV, Candidate, Company, storage provider, storage key, resource type, file name, content type, file size, and `granted_at`. These identity fields **shall** be immutable. |
| CVACL-004 | A grant **shall** be revocable exactly once. Revocation **shall** set `revoked_at` and a `revoked_reason` in the same update. |
| CVACL-005 | An open-ended grant (`expires_at IS NULL`) **shall** be bounded exactly once when the Candidate deletes the library CV while the Application is in an active review stage. A bounded or revoked grant **shall not** be extended or rewritten. |
| CVACL-006 | A grant row **shall not** be deleted by application code. Database triggers **shall** reject deletion, identity rewrites, expiry rewrites, and revocation rewrites. |
| CVACL-007 | A Company read **shall** require a live grant for the authenticated Company and the requested Application. Expired and revoked grants **shall** fail closed. |
| CVACL-008 | A failed grant check **shall** use the same concealed not-found response as an absent, other-Company, or unassigned Application. It **shall not** reveal whether a grant exists but expired. |

### 3.2 Submission

| ID | Requirement |
|---|---|
| CVACL-010 | Submission **shall** keep the Candidate's library object and **shall not** copy the CV bytes. |
| CVACL-011 | Submission **shall** create one `SHARED` snapshot whose `cv_storage_key` is the library `public_id`. |
| CVACL-012 | Submission **shall** create one open-ended access grant in the same database transaction as the Application, snapshot, and initial status history. |
| CVACL-013 | Submission **shall** re-read the library object and validate format, content type, and size before writing the grant. A missing object **shall** fail the submission with no Application, snapshot, or grant row. |
| CVACL-014 | Submission **shall** lock the active `candidate_cvs` row so a concurrent deletion cannot begin removing bytes for an Application that is being recorded. |
| CVACL-015 | Submission **shall** ignore any client-supplied Company, storage key, or access metadata. |
| CVACL-016 | Pre-existing Applications **shall** keep their `LEGACY_COPY` snapshot and copied object. No grant backfill **shall** be required. |

### 3.3 Candidate library deletion

| ID | Requirement |
|---|---|
| CVACL-020 | The Candidate **shall** be able to remove only a CV owned by the authenticated Candidate. |
| CVACL-021 | Removal **shall** mark the row with `deleted_at` and clear the default flag. The row **shall** remain as a tombstone. |
| CVACL-022 | The removed CV **shall** immediately disappear from listing, quota counting, default selection, and Candidate download endpoints. |
| CVACL-023 | If the CV has a live grant whose Application is `SUBMITTED`, `UNDER_REVIEW`, `SHORTLISTED`, or `INTERVIEWING`, removal **shall** set `expires_at = deleted_at + access-grant TTL` on that grant. The bytes **shall** be retained until the window ends. |
| CVACL-023a | If the CV has a live grant whose Application is `HIRED`, removal **shall** revoke that grant immediately with reason `CANDIDATE_CV_DELETED`; no post-deletion window applies. `INACTIVE` and terminal Applications are already revoked. |
| CVACL-024 | If the CV has no grant requiring a window, removal **shall** delete the Cloudinary object immediately and record `storage_deleted_at`. |
| CVACL-025 | An immediate Cloudinary failure **shall** not fail the Candidate request. The tombstone **shall** remain and the retention worker **shall** retry. |
| CVACL-026 | Deleting the default CV **shall** leave no active default. Another active CV may then become default under the existing one-default rule. |

### 3.4 Company reads

| ID | Requirement |
|---|---|
| CVACL-030 | A Company **shall** read a `SHARED` CV only while its grant is live. |
| CVACL-031 | A Company **shall** read a `LEGACY_COPY` CV from the historical application copy while the existing Application access and terminal-status rules pass. |
| CVACL-032 | A `SHARED` snapshot **shall not** fall back to `cv_storage_key` when the grant is missing, expired, revoked, or for another Company. |
| CVACL-033 | The inline viewer **shall** continue to accept only PDF and **shall** return `406` for DOC and DOCX. |
| CVACL-034 | The Company download endpoint **shall** accept PDF, DOC, and DOCX and **shall** return the authorized CV bytes as an attachment. |
| CVACL-035 | A successful inline read or binary download **shall** record `APPLICATION_CV_VIEWED`. |
| CVACL-036 | `REJECTED`, `WITHDRAWN`, and `INACTIVE` Applications **shall** conceal CV access. `HIRED` Applications **shall** remain readable while the other access checks pass. |
| CVACL-037 | A rejected `LEGACY_COPY` whose bytes were purged **shall** return the concealed not-found response, while its snapshot metadata remains readable. |

### 3.5 Revocation

| ID | Requirement |
|---|---|
| CVACL-040 | Moving an Application to `REJECTED` **shall** revoke its live grant with reason `APPLICATION_CLOSED` and enqueue deletion of its legacy application copy (if any). |
| CVACL-041 | Candidate withdrawal **shall** revoke the grant with reason `APPLICATION_CLOSED`. A withdrawn legacy copy is retained. |
| CVACL-042 | Revocation **shall** be idempotent: a terminal retry **shall** not fail when the grant is already revoked. |
| CVACL-043 | Revocation **shall not** delete the library object; the retention worker removes it once it is also deleted and unshared. |

### 3.6 Retention and purge

| ID | Requirement |
|---|---|
| CVACL-050 | The system **shall** purge a library object only when the CV is deleted, `storage_deleted_at` is null, and no live grant remains. |
| CVACL-051 | The purge worker **shall** process a bounded batch, lock claimed rows with `FOR UPDATE ... SKIP LOCKED`, and commit each deletion independently. |
| CVACL-052 | A successful purge **shall** set `storage_deleted_at`. A failed purge **shall** keep the tombstone so the next tick retries the same object. |
| CVACL-053 | A delayed or failing purge **shall not** extend access; access is enforced at read time, not by object presence. |
| CVACL-054 | The worker **shall** be disableable for tests and constrained deployments. |
| CVACL-055 | The system **shall** log purge outcomes by CV id without logging storage keys, signed URLs, or file bytes. |

### 3.7 Inactivity and reactivation

| ID | Requirement |
|---|---|
| CVACL-070 | The system **shall** measure inactivity from `applications.status_changed_at`, which is set on every status transition and backfilled from append-only status history. |
| CVACL-071 | If no status transition occurs for `application.review.inactivity-ttl` (default 30 days), the system **shall** move an active Application (`SUBMITTED`, `UNDER_REVIEW`, `SHORTLISTED`, `INTERVIEWING`) to `INACTIVE`. `HIRED`, `REJECTED`, and `WITHDRAWN` **shall** be excluded. |
| CVACL-072 | The inactivity worker **shall** process a bounded batch, lock claimed rows with `FOR UPDATE ... SKIP LOCKED`, re-check the status clock, and commit each Application independently. |
| CVACL-073 | Becoming `INACTIVE` **shall** revoke the current grant epoch with reason `APPLICATION_INACTIVE` and append a status-history row with a null actor. |
| CVACL-074 | `INACTIVE` **shall** deny Company CV reads with the concealed not-found response, while Company detail visibility remains until a terminal status. |
| CVACL-075 | A Company Owner or currently assigned Recruiter **shall** reactivate an `INACTIVE` Application only through `UNDER_REVIEW`. Manual transition directly to `INACTIVE` **shall** be rejected. |
| CVACL-076 | Reactivation **shall** start a new grant epoch only when the snapshot is `SHARED` and the library CV is still active. If the Candidate deleted the CV, no new grant is created. |
| CVACL-077 | A Candidate **shall** be able to withdraw an `INACTIVE` Application. |
| CVACL-078 | `INACTIVE` **shall** be a valid list filter and response status without exposing CV bytes. |
| CVACL-079 | The inactivity result **shall** notify only the Company owner and currently assigned Recruiters with `APPLICATION_INACTIVE`. The Candidate **shall** see the status but receive no notification. |

### 3.8 Rejected legacy-copy purge

| ID | Requirement |
|---|---|
| CVACL-080 | On `REJECTED`, the system **shall** delete the application-specific `LEGACY_COPY` object. It **shall not** delete a `SHARED` Candidate library object. |
| CVACL-081 | The purge worker **shall** claim rejected legacy snapshots with `cv_purged_at IS NULL` in bounded batches and delete only keys under `applications/{applicationId}/cv/`. |
| CVACL-082 | A successful deletion **shall** set `application_snapshots.cv_purged_at`; snapshot metadata (file name, type, size, storage key) **shall** be retained. |
| CVACL-083 | A failed deletion **shall** leave `cv_purged_at` null so the next tick retries. |
| CVACL-084 | The same worker **shall** backfill Applications rejected before this amendment. `WITHDRAWN` Applications **shall** retain their legacy copy. |
| CVACL-085 | After purge, the Candidate and Company CV download endpoints **shall** return not found; the retained metadata remains available to existing detail responses. |

### 3.9 Upload access mode

| ID | Requirement |
|---|---|
| CVACL-060 | New upload signatures **shall** include `access_mode=authenticated` in the signed parameters. |
| CVACL-061 | Registration **shall** reject an asset whose provider access mode is not `authenticated`. |
| CVACL-062 | Pre-existing library objects uploaded with public delivery **shall** remain readable. They are a documented residual risk, not silently rewritten. |

## 4. Data model

### 4.1 `applications` additions

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `status` | VARCHAR(30) | NOT NULL | Adds `INACTIVE` to the lifecycle set; allowed values are validated in the application layer, not by a database CHECK. |
| `status_changed_at` | TIMESTAMPTZ | NOT NULL, default `now()` | Last status transition; backfilled from `max(application_status_history.occurred_at)` with `applied_at` fallback. |

Index `ix_applications_inactivity` on `(status_changed_at)` where `status IN
('SUBMITTED','UNDER_REVIEW','SHORTLISTED','INTERVIEWING')`. Allowed transitions include active →
`INACTIVE` and `INACTIVE` → `UNDER_REVIEW`/`REJECTED`/`WITHDRAWN`; a direct `INACTIVE` → `HIRED`
transition is rejected by the application layer. The database does not enforce a status-transition
CHECK.

### 4.2 `candidate_cvs` additions

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `deleted_at` | `TIMESTAMPTZ` | Nullable | Candidate removal time; acts as the tombstone marker. |
| `storage_deleted_at` | `TIMESTAMPTZ` | Nullable | Provider object deletion time; null while purge is pending. |

Indexes:

- `uk_candidate_cvs_one_default` is replaced by a partial unique index on `(candidate_id)` where
  `is_default AND deleted_at IS NULL`;
- `ix_candidate_cvs_retention` on `(deleted_at)` where `deleted_at IS NOT NULL AND
  storage_deleted_at IS NULL`.

### 4.3 `candidate_cv_access_grants`

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | UUID | PK | Assigned by the application workflow. |
| `application_id` | UUID | NOT NULL, FK → `applications(id)` | One or more epochs per Application. |
| `epoch` | INTEGER | NOT NULL, default 1, unique with `application_id` | Reactivation starts the next epoch. |
| `cv_id` | UUID | Nullable, FK → `candidate_cvs(id)` | Shared library object. |
| `candidate_id` | UUID | NOT NULL, FK → `candidate_profiles(id)` | Owning Candidate. |
| `company_id` | BIGINT | NOT NULL, FK → `companies(id)` | Receiving Company. |
| `scope` | VARCHAR(30) | NOT NULL, default `APPLICATION_REVIEW` | Closed set. |
| `storage_provider` | VARCHAR(30) | NOT NULL, `CLOUDINARY` only | Provider gate. |
| `storage_key` | VARCHAR(255) | NOT NULL | Library `public_id`, never an application copy. |
| `resource_type` | VARCHAR(20) | NOT NULL, `image` or `raw` | Provider lookup. |
| `file_name` | VARCHAR(255) | NOT NULL, non-blank | Display/download name. |
| `content_type` | VARCHAR(100) | NOT NULL, PDF/DOC/DOCX | Format source. |
| `file_size` | BIGINT | NOT NULL, `> 0` | Verified bytes. |
| `granted_at` | TIMESTAMPTZ | NOT NULL | Submission or reactivation instant. |
| `expires_at` | TIMESTAMPTZ | Nullable, `> granted_at` | Null while the CV stays in the library. |
| `revoked_at` | TIMESTAMPTZ | Nullable | Set once on terminal/inactive status or HIRED deletion. |
| `revoked_reason` | VARCHAR(30) | Nullable, `APPLICATION_CLOSED`, `ADMIN`, `APPLICATION_INACTIVE`, `CANDIDATE_CV_DELETED` | Paired with `revoked_at`. |

Indexes:

- `uk_cv_grant_application_epoch` unique on `(application_id, epoch)`;
- `uk_cv_grant_live_application` unique on `(application_id)` where `revoked_at IS NULL` — at most one live epoch;
- `ix_cv_grant_cv` on `(cv_id)` where `revoked_at IS NULL`;
- `ix_cv_grant_expiry` on `(expires_at)` where `revoked_at IS NULL AND expires_at IS NOT NULL`;
- `ix_cv_grant_company` on `(company_id, application_id)`.

Trigger `trg_candidate_cv_access_grant` rejects `DELETE` and any change to identity, granted time,
expiry (after it is set), or revocation (after it is set). The only permitted transitions are
`expires_at` from null to a value and `revoked_at`/`revoked_reason` from null to values.

### 4.4 `application_snapshots.cv_storage_mode` and purge marker

| Value | Meaning |
|---|---|
| `LEGACY_COPY` | Historical application-specific copy. Default for pre-existing rows. Company reads use `cv_storage_key`. |
| `SHARED` | New shared library reference. Company reads require a live grant, never `cv_storage_key`. |

`cv_purged_at TIMESTAMPTZ` marks a deleted rejected legacy copy. Snapshot metadata remains intact.
The snapshot trigger permits exactly one transition, `cv_purged_at` from `NULL` to a timestamp, and
rejects every other update/delete. Index `ix_application_snapshots_cv_purge` on `(captured_at)`
where `cv_purged_at IS NULL AND cv_storage_mode = 'LEGACY_COPY'`.

The columns and indexes above are added by `V2026.11.17.108__candidate_cv_access_grants.sql` and
`V2026.11.18.109__application_inactive_and_cv_purge.sql`.

## 5. API specification

### 5.1 Candidate library deletion

#### `DELETE /api/candidate/cvs/{cvId}`

Removes one owned CV from the Candidate library.

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "CV deleted successfully",
  "data": null
}
```

The response succeeds even when the Cloudinary deletion is deferred. The CV is immediately absent
from listing, default selection, quota counting, and Candidate download endpoints. Active
Applications keep the bounded review window; `HIRED` Applications lose company access immediately;
`INACTIVE` and terminal Applications are already revoked.

### 5.2 Candidate submitted-CV download

#### `GET /api/candidate/applications/{applicationId}/cv/download-url`

Returns a signed URL for the submitted CV of the authenticated Candidate.

- `SHARED`: returns a URL only while the source `candidate_cvs` row is active.
- `LEGACY_COPY`: returns a URL while the rejected copy has not been purged.
- URL lifetime is 15 minutes.
- After the Candidate deletes the shared CV, or after a rejected legacy copy is purged, this
  endpoint returns `404` (`CV_NOT_FOUND`).

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Success",
  "data": {
    "url": "https://...",
    "expiresAt": "2026-11-17T10:15:00Z",
    "fileName": "CV.pdf"
  }
}
```

### 5.3 Company inline CV view

#### `GET /api/companies/me/jobs/{jobId}/applications/{applicationId}/cv`

Returns the CV as an inline binary response.

- PDF only; DOC and DOCX return `406` (`CV_PREVIEW_UNSUPPORTED`).
- Requires current Owner or assigned Recruiter access to the Job Posting.
- `SHARED` requires a live grant for the current Company.
- `LEGACY_COPY` reads the historical copy while it has not been purged.
- `REJECTED`, `WITHDRAWN`, and `INACTIVE` return the concealed `404` (`JOB_NOT_FOUND`).
- A newly assigned Recruiter of the receiving Company may read while the grant is live and the
  assignment is current; the grant is company-scoped, not bound to the original recruiter.
- Success records `APPLICATION_CV_VIEWED`.
- `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.

### 5.4 Company CV download

#### `GET /api/companies/me/jobs/{jobId}/applications/{applicationId}/cv/download-url`

Returns the authorized CV bytes as an attachment for PDF, DOC, or DOCX.

**Response `200 OK`**

- Media type is the stored CV content type.
- `Content-Disposition: attachment` carries the submitted file name.
- The response uses `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.
- The bytes are read only after the same grant and application-access checks as the inline view.
- A successful read records `APPLICATION_CV_VIEWED`.
- No storage key or signed provider URL appears in the response.

The route path is retained for client compatibility; it no longer returns a JSON URL envelope.

### 5.5 Application detail access metadata

`GET /api/companies/me/jobs/{jobId}/applications/{applicationId}` includes:

- `canViewCv`: whether the authenticated Company may currently read the submitted CV through
  either CV endpoint. It is false for `REJECTED`, `WITHDRAWN`, `INACTIVE`, missing/expired grants,
  and purged copies.
- `reviewWindowEndsAt`: the live shared grant expiry after Candidate deletion; null when
  open-ended or unavailable.
- `inactivityDeadline`: `statusChangedAt` plus `application.review.inactivity-ttl` for active
  stages; null for `HIRED`, terminal, and `INACTIVE` Applications.

`GET /api/candidate/applications/{applicationId}` includes `canViewCv` for the Candidate’s own
submitted-CV download. The Company-only `reviewWindowEndsAt` and `inactivityDeadline` are null.

### 5.6 Application status and notify

`INACTIVE` is a valid value in the Candidate and Company list/status filters. The Candidate sees
the status in list/detail; only the Company owner and currently assigned Recruiters receive the
`APPLICATION_INACTIVE` notification. Reactivation uses the existing status-update endpoint with
`status = UNDER_REVIEW`; a manual request targeting `INACTIVE` is rejected.

## 6. Business rules

| ID | Rule |
|---|---|
| BR-CVACL-01 | The Candidate owns the CV; the receiving Company receives a limited, purpose-bound permission. |
| BR-CVACL-02 | A Company's permission is scoped to one Application and one Company. It is not a general Candidate CV access right. |
| BR-CVACL-03 | Grant identity is append-only. Only the review window may start once and revocation may happen once. |
| BR-CVACL-04 | A Candidate's deletion stops their own access immediately. The bounded Company window applies only to active review stages; a HIRED Application loses access immediately. |
| BR-CVACL-05 | The Company's review window is one month by default and starts at Candidate deletion, not at submission. |
| BR-CVACL-06 | `REJECTED`, `WITHDRAWN`, and `INACTIVE` states end Company access immediately. `HIRED` keeps access while the other checks pass. |
| BR-CVACL-07 | Legacy Applications remain readable from their copied object until a rejection purge deletes only the bytes; the snapshot metadata is retained. They are never switched to a grant they never had. |
| BR-CVACL-08 | A missing, expired, revoked, or inactive grant is indistinguishable from an inaccessible Application for the Company. |
| BR-CVACL-09 | Object purge is a storage-hygiene step. It is not the access-control mechanism. |
| BR-CVACL-10 | A purge failure is retried; it must not resurrect access or delete a grant. |
| BR-CVACL-11 | New uploads use authenticated delivery. Pre-existing public objects are documented residual risk. |
| BR-CVACL-12 | CV bytes, storage keys, and signed URLs are never written to application logs. |
| BR-CVACL-13 | Inactivity is measured from the last status transition, not from CV views, notes, or submissions. |
| BR-CVACL-14 | Reactivation is explicit and starts a new grant epoch; it never revives a revoked epoch. |
| BR-CVACL-15 | Rejection purge removes bytes only. Snapshot metadata and grant audit rows remain. |
| BR-CVACL-16 | The inactivity notification is Company-only; the Candidate sees the status without a notification. |

## 7. Error catalogue

| Error code | HTTP | Constant | Trigger |
|---|---|---|---|
| `40100` | 401 | `UNAUTHENTICATED` | Missing or invalid access token. |
| `40302` | 403 | `NOT_COMPANY_MEMBER` | No active Company membership. |
| `40303` | 403 | `INSUFFICIENT_COMPANY_ROLE` | Admin, non-owner Owner, or unsupported role. |
| `40405` | 404 | `CV_NOT_FOUND` | Candidate CV absent, removed, or owned by another Candidate. |
| `40478` | 404 | `JOB_NOT_FOUND` | Concealed Application, Job, Company scope, `INACTIVE` status, expired grant, revoked grant, or purged legacy copy. |
| `40600` | 406 | `CV_PREVIEW_UNSUPPORTED` | Inline view requested for DOC or DOCX. |
| `50300` | 503 | `CV_STORAGE_UNAVAILABLE` | Unsupported provider or provider access failure. |

`APPLICATION_CV_COPY_FAILED` (`50301`) is no longer produced because no copy is attempted. The
constant remains in the enum for wire compatibility with older clients.

## 8. Non-functional requirements

| ID | Category | Requirement |
|---|---|---|
| NFR-CVACL-01 | Security | Every Company CV read **shall** be authorized server-side from the grant, never from a client-supplied key or id. |
| NFR-CVACL-02 | Security | Signed upload parameters **shall** include `access_mode=authenticated` so a client cannot downgrade delivery. |
| NFR-CVACL-03 | Privacy | Access **shall** end automatically within the configured window after Candidate deletion for active stages, immediately for `HIRED`, and immediately when an Application becomes `INACTIVE`. |
| NFR-CVACL-04 | Integrity | Grant identity, epoch, expiry, and revocation transitions **shall** be enforced by the database. |
| NFR-CVACL-05 | Reliability | Purge and inactivity failures **shall** be retried without failing the Candidate request or extending access. |
| NFR-CVACL-06 | Availability | Grant checks **shall** use indexed lookups on `(application_id)`, `(cv_id)`, and `(company_id, application_id)`; inactivity uses the partial `status_changed_at` index. |
| NFR-CVACL-07 | Observability | Grant denials, inactivity transitions, and purge retries **shall** be logged by identifiers only. |
| NFR-CVACL-08 | Compatibility | The migration is additive except for the default-CV index replacement, the new `INACTIVE` status value, and the snapshot purge marker. Legacy rows keep working. |
| NFR-CVACL-09 | Auditability | Inline Company reads **shall** continue to record `APPLICATION_CV_VIEWED`; inactivity and rejection transitions remain in append-only status history. |
| NFR-CVACL-10 | Correctness | A shared snapshot **shall** never fall back to `cv_storage_key` when the grant is not live. |
| NFR-CVACL-11 | Correctness | Reactivation **shall** create a new epoch only while the shared library CV is active. |
| NFR-CVACL-12 | Legal compliance | Rejected legacy copies **shall** be deleted in bounded, retryable batches while metadata is retained for traceability. |

## 9. Configuration

| Property | Environment variable | Default | Purpose |
|---|---|---|---|
| `candidate.cv.access-grant-ttl` | `CANDIDATE_CV_ACCESS_GRANT_TTL` | `30d` | Post-deletion review window for active Applications. Spring `Duration` syntax. |
| `candidate.cv.retention.poll-ms` | `CANDIDATE_CV_RETENTION_POLL_MS` | `60000` | Delay between library-object retention batches. |
| `candidate.cv.retention.worker-enabled` | `CANDIDATE_CV_RETENTION_WORKER_ENABLED` | `true` | Enables the scheduled library purge worker. |
| `application.review.inactivity-ttl` | `APPLICATION_REVIEW_INACTIVITY_TTL` | `30d` | Time without a status transition before `INACTIVE`. |
| `application.review.inactivity-poll-ms` | `APPLICATION_REVIEW_INACTIVITY_POLL_MS` | `60000` | Inactivity worker delay. |
| `application.review.inactivity-worker-enabled` | `APPLICATION_REVIEW_INACTIVITY_WORKER_ENABLED` | `true` | Enables the inactivity worker. |
| `application.review.inactivity-batch-size` | `APPLICATION_REVIEW_INACTIVITY_BATCH_SIZE` | `100` | Inactivity batch size, 1–1000. |
| `application.cv.purge.poll-ms` | `APPLICATION_CV_PURGE_POLL_MS` | `60000` | Rejected legacy-copy purge delay. |
| `application.cv.purge.worker-enabled` | `APPLICATION_CV_PURGE_WORKER_ENABLED` | `true` | Enables the rejected-copy purge worker. |
| `application.cv.purge.batch-size` | `APPLICATION_CV_PURGE_BATCH_SIZE` | `100` | Rejected-copy batch size, 1–1000. |

The library retention batch size is fixed at 20. Tests disable the workers and call
`CandidateCvRetentionService.purgeDue`, `ApplicationInactivityService.expireDue`, and
`ApplicationCvPurgeService.purgeDue` directly.

## 10. Compatibility and residual risks

| Risk | Disposition |
|---|---|
| Pre-existing Cloudinary objects uploaded with public delivery remain directly reachable if their URL is known. | Accepted residual risk; new uploads are authenticated. A future approved backfill can migrate the mode. |
| Pre-existing Applications keep permanent application-specific copies. | Legacy compatibility; a rejection now deletes the copy bytes while retaining metadata. No grant backfill. |
| A Candidate whose CV is retained during the review window cannot re-upload the exact same object, but may upload a new one. | Expected; quota counts active rows only. |
| Grant rows prevent hard deletion of their Candidate CV and Candidate profile while the FK exists. | Tombstones are the supported lifecycle; account deletion remains a separate approved scope. |
| Grant expiry is enforced by application queries; there is no scheduled sweep that rewrites expired rows. | Access fails closed at read time; the expiry index supports future maintenance. |
| Purge is eventual (poll interval, retries). | Bytes may outlive the review window by at most the worker delay and retry backoff; access is already denied. |
| The legal "unless a retention agreement exists" exception is not implemented. | Deferred by the requesting human user; rejection always purges the legacy copy now. A future approved agreement flow must capture candidate consent and an expiry. |
| A `SHARED` rejected CV has no Company copy to delete; its library object remains under the Candidate's control. | Access is revoked; the Candidate may delete the library object through the normal tombstone flow. Legal review should confirm this scope. |
| Inactivity revokes access even if the Candidate-deletion window has not ended. | The earlier end wins by design. |
| `INACTIVE` is a breaking API status value. | Clients must handle it; approved by the requesting human user on 2026-11-18. |

## 11. Verification

| Boundary | Tests |
|---|---|
| CV service behavior | `CandidateCvServiceTest` — shared submission reference, authenticated-only registration, soft delete, active-stage review window, HIRED immediate revocation, immediate purge, deferred purge, owner scoping. |
| Retention | `CandidateCvRetentionServiceTest` — batch deletion, failure retry, missing rows. |
| Inactivity | `ApplicationInactivityServiceTest` — stale boundary, re-check, inactive/terminal exclusions, grant revocation, owner + assigned-recruiter notification. |
| Rejected-copy purge | `ApplicationCvPurgeServiceTest` — legacy deletion, unsafe-key guard, retry, already-purged skip. |
| Storage adapter | `CloudinaryCandidateCvStorageTest` — signed `access_mode`, failure mapping. |
| Application behavior | `JobApplicationServiceTest` — grant creation, shared read, legacy read, revocation, candidate access after deletion. |
| HTTP contract | `JobApplicationHttpTest` — submission grant, Candidate access loss, Company inline read, binary download, rejection revocation, legacy application, `INACTIVE` concealment, reactivation epoch, newly assigned recruiter read, company-only inactivity notification, rejected-copy purge, withdrawal from `INACTIVE`. |
| Database boundary | `JobApplicationMigrationTest` — grant epochs and partial live uniqueness, immutability trigger, live-grant query, purge query, snapshot mode, purge-marker trigger, inactivity query, `INACTIVE` status/history edges, `status_changed_at`. |
| Controller contract | `CandidateCvControllerTest` — delete and download responses. |

Local verification uses `-Djava.version=21` because the current environment provides Java 21 while
the project declares Java 25. Use an isolated test database via `BACKEND_TEST_DATABASE_URL` when a
shared test database already applied migrations from another branch.

## 12. Approval record

| Date | Actor | Decision |
|---|---|---|
| 2026-11-17 | Requesting human user | Approved the requirement amendment: grant-based CV access, no per-application copy, one-month Company review window after Candidate deletion, and automatic purge. |
| 2026-11-18 | Requesting human user | Approved the second amendment: `INACTIVE` after 30 days without a status update; reversible reactivation with grant epochs; post-deletion window only for active stages (HIRED immediate); rejection deletes the legacy copy and keeps snapshot metadata; backfill for already-rejected Applications; company-only inactivity notification; retention agreement deferred. |
| 2026-11-20 | Requesting human user | Approved replacing the Company download URL with an authenticated binary attachment download for PDF, DOC, DOCX, recording `APPLICATION_CV_VIEWED`. |
| Pending | chiko | Formal approval of this specification. |
