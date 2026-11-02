# Candidate CV Upload — Software Requirements Specification

| Field | Value |
|---|---|
| **Specification ID** | GW-SR-CAND-02 |
| **Project** | GuardWork |
| **Module** | Candidate CV Library |
| **File** | `candidate-cv-upload-spec.md` |
| **Version** | 0.1.0 |
| **Date** | 2026-10-31 |
| **Last Updated** | 2026-10-31 |
| **Owner** | GuardWork thesis team |
| **Approver** | chiko |
| **Status** | Draft — approval pending |
| **Related use case** | UC-CAN-03 — Tải lên hồ sơ xin việc mới (primary); UC-CAN-02 — Quản lý hồ sơ xin việc (listing and download) |
| **Related brainstorm** | `GW-BS-CAND-02` — Candidate CV Upload & Management |
| **Related frontend guide** | [`candidate-profile-cv-frontend-integration.md`](../../../../frontend/candidate-profile-cv-frontend-integration.md) |

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Overall Description](#2-overall-description)
3. [Data Models](#3-data-models)
4. [Functional Requirements](#4-functional-requirements)
5. [Flow Diagrams](#5-flow-diagrams)
6. [Business Rules](#6-business-rules)
7. [Error Catalogue](#7-error-catalogue)
8. [API Specification](#8-api-specification)
9. [Infrastructure Architecture](#9-infrastructure-architecture)
10. [Non-Functional Requirements](#10-non-functional-requirements)
11. [Implementation Alignment and Open Gaps](#11-implementation-alignment-and-open-gaps)
12. [Verification](#12-verification)
13. [Approval Record](#13-approval-record)

---

## 1. Introduction

### 1.1 Purpose

This specification defines how an authenticated Candidate uploads a new CV to GuardWork. It covers
privacy consent, the two-step Cloudinary direct-upload flow, server-side verification of the stored
asset, metadata registration, quota enforcement, first-CV default behavior, listing, and short-lived
download URLs. It refines UC-CAN-03 and the library-view portion of UC-CAN-02 in
[`docs/feat/candidates/usecase.md`](../../feat/candidates/usecase.md).

### 1.2 Scope

| Sub-feature | Responsibility |
|---|---|
| **Privacy consent** | Require explicit confirmation before issuing an upload signature. |
| **Signed upload URL** | Issue a server-controlled Cloudinary upload target inside the Candidate's folder. |
| **Direct file upload** | The browser uploads the selected file directly to Cloudinary; file bytes do not pass through the GuardWork backend. |
| **CV registration** | Verify the stored Cloudinary asset and persist CV metadata in `candidate_cvs`. |
| **Quota and default** | Limit a Candidate to five CVs and mark the first CV as default. |
| **CV listing** | Return only the authenticated Candidate's CVs, newest first. |
| **Download URL** | Issue a 15-minute signed URL only to the CV owner. |

Out of scope:

- deleting and default CV management are implemented by separate endpoints and are documented in
  UC-CAN-05 and UC-CAN-06; they are not part of this upload specification's request/response contract.
- renaming, editing, or replacing an existing CV;
- copying a CV into an application snapshot (covered by
  [`application-submission-spec.md`](application-submission-spec.md));
- recruiter access to a Candidate's library CV; recruiters read the application copy through a
  separately approved application workflow;
- OCR, virus scanning, or automatic detection of sensitive data inside the file.

### 1.3 Decisions confirmed for this specification

| Decision | Value |
|---|---|
| Upload architecture | Backend issues a signed Cloudinary upload target; the browser uploads directly to Cloudinary, then calls GuardWork to register the asset. |
| Storage ownership | The server derives `publicId` as `candidates/{candidateId}/cvs/{randomUuid}` and ignores client-supplied folder/public-id values. |
| File validation | Allowed formats are PDF, DOC, and DOCX. The server reads the stored Cloudinary asset and validates its format, content type, and byte size; it does not trust client-submitted size/type. |
| Maximum size | 10 MB (`10,485,760` bytes). |
| Quota | At most five registered CVs per Candidate. |
| Default CV | The first registered CV becomes default; later CVs are not default. |
| Success shape | All CV endpoints return the standard `ApiResponse` envelope. The controller now wraps all four endpoints in the working tree; that change is uncommitted and must be committed and verified. |
| Consent | `consentGiven` is collected by the frontend only. The backend keeps the field optional for compatibility and does not enforce or persist consent. |

---

## 2. Overall Description

### 2.1 High-Level Flow

```text
Candidate                 GuardWork API                 Cloudinary              PostgreSQL
   |                           |                           |                       |
   |-- consent + request ----->|                           |                       |
   |                           |-- check quota ------------|---------------------->|
   |                           |-- sign public_id ---------|                       |
   |<-- upload signature ------|                           |                       |
   |-- multipart file -----------------------------------> |                       |
   |<-- upload result ------------------------------------ |                       |
   |-- register metadata ----->|                           |                       |
   |                           |-- read asset ------------>|                       |
   |                           |<-- format / bytes / type -|                       |
   |                           |-- insert candidate_cvs ---|---------------------->|
   |<-- 201 CV response -------|                           |                       |
```

### 2.2 Lifecycle

Only registered CVs are persisted. The upload has two transient phases before registration.

```text
(no CV)
   |
   | POST /upload-url {consentGiven:true}
   v
PENDING_UPLOAD  (signature issued; no DB row)
   |
   | browser uploads to Cloudinary
   v
UPLOADED        (asset exists in Cloudinary; still no DB row)
   |
   | POST /api/v1/candidate/cvs {fileName, publicId}
   v
REGISTERED      (candidate_cvs row)
   |
   +--> listed by GET /api/v1/candidate/cvs
   +--> default when it is the first CV
   +--> downloadable through a 15-minute signed URL
```

Failure paths:

- Signature request fails validation or quota → no signature, no asset.
- Cloudinary upload fails → no registration call, no DB row.
- Registration validation fails → the uploaded asset is deleted on a best-effort basis; no DB row.
- Duplicate registration → the existing asset and row are preserved; `40902` is returned.
- Client uploads but never calls registration → an orphaned Cloudinary asset may remain. A later
  cleanup job is required; it is not part of this scope.

### 2.3 Response Envelope

Successful responses use `ApiResponse`:

```json
{
  "status": 200,
  "message": "Success",
  "data": { }
}
```

Errors use `ErrorApiResponse`:

```json
{
  "message": "Only PDF, DOC, and DOCX files up to 10 MB are accepted.",
  "status": 40021,
  "detailed_message": "Optional diagnostic detail"
}
```

`status` is the application error code, not the HTTP status. Clients must branch on the HTTP status
and the application code, not on the message text.

### 2.4 Public vs Protected Endpoints

All CV endpoints require `Authorization: Bearer <jwt>` and an authenticated Candidate account.

- The Candidate identity comes from the authenticated principal.
- `candidateId` is never trusted from the body or query string.
- A legacy optional `candidateId` may be supplied for compatibility, but it must equal the
  authenticated Candidate's profile id.
- Download URLs are issued only to the owner of the CV.

---

## 3. Data Models

### 3.1 `candidate_cvs` (PostgreSQL)

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | PK | CV identity. |
| `candidate_id` | `UUID` | NOT NULL, FK → `candidate_profiles(id)` ON DELETE CASCADE | Owner. |
| `file_name` | `VARCHAR(255)` | NOT NULL | Original name, including extension. |
| `file_size` | `BIGINT` | NOT NULL, `> 0` | Verified byte size from Cloudinary. |
| `content_type` | `VARCHAR(100)` | NOT NULL | One of the three allowed MIME types. |
| `public_id` | `VARCHAR(255)` | NOT NULL, unique | Cloudinary storage key. |
| `is_default` | `BOOLEAN` | NOT NULL, default `false` | At most one default per candidate. |
| `created_at` | `TIMESTAMPTZ` | NOT NULL, default `now()` | Converted by `V2026.10.31.90__make_candidate_cv_timestamps_timestamptz.sql`. |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL, default `now()` | Updated on row changes. |

### 3.2 Storage key format

```text
candidates/{candidateId}/cvs/{randomUuid}
```

- `candidateId` is the authenticated Candidate's `candidate_profiles.id`.
- `randomUuid` is generated by the server.
- The client cannot select the folder or public id.
- Application snapshots use a separate key:
  `applications/{applicationId}/cv/{randomUuid}.{pdf|doc|docx}`.

### 3.3 Cloudinary asset metadata

The backend reads these values from Cloudinary after upload:

| Field | Use |
|---|---|
| `public_id` | Must start with the Candidate's folder; becomes the stored storage key. |
| `format` | `pdf`, `doc`, or `docx`; must be in the allowlist. |
| `content_type` | Must map exactly to the format. |
| `bytes` | Must be greater than 0 and at most 10 MB. |
| `resource_type` | `image` for PDF, `raw` for DOC/DOCX; used for lookup, deletion, and download URLs. |

### 3.4 Indexes and Constraints

| Index / constraint | Table | Column(s) | Purpose |
|---|---|---|---|
| `fk_candidate_cvs_candidate_id` | `candidate_cvs` | `candidate_id` | Ownership and cascade cleanup. |
| `uk_candidate_cvs_one_default` | `candidate_cvs` | `candidate_id` WHERE `is_default` | At most one default CV per candidate. |
| `uk_candidate_cvs_public_id` | `candidate_cvs` | `public_id` | One row per Cloudinary object; duplicate-upload guard. |
| `idx_candidate_cvs_candidate_created_at` | `candidate_cvs` | `candidate_id, created_at DESC` | Newest-first list. |
| `idx_candidate_cvs_candidate_id` | `candidate_cvs` | `candidate_id` | Legacy lookup index. |

---

## 4. Functional Requirements

### 4.1 Consent and Upload Signature

| ID | Requirement |
|---|---|
| CVU-01 | The system **shall** derive the Candidate from the authenticated principal and **shall not** accept a caller-selected owner. |
| CVU-02 | Consent is collected and enforced by the frontend. The backend no longer validates, requires, or returns `40020` for `consentGiven`; the field remains optional for backward compatibility, and `folder`/`publicId` are ignored. |
| CVU-03 | The system **shall** check the Candidate's registered CV count before issuing a signature. At five CVs, it **shall** return `40022`. |
| CVU-04 | The system **shall** create a skeleton Candidate profile if one does not exist, then use its id as the storage folder. |
| CVU-05 | The system **shall** generate `publicId = candidates/{candidateId}/cvs/{randomUuid}` and **shall** ignore client-supplied `folder` and `publicId` values. |
| CVU-06 | The system **shall** return the Cloudinary upload URL, API key, timestamp, signature, and server-generated public id. |

### 4.2 File Upload

| ID | Requirement |
|---|---|
| CVU-10 | The browser **shall** upload the file directly to the returned Cloudinary URL using `multipart/form-data`. |
| CVU-11 | The upload request **shall** include exactly the signed parameters (`public_id`, `timestamp`), the API key, the signature, and the file. |
| CVU-12 | The browser **shall** pre-check the extension and MIME type against the allowlist and reject files larger than 10 MB before starting an upload. |
| CVU-13 | If the Cloudinary upload fails, the application **shall not** call the registration endpoint and **shall** show a retryable error. |

### 4.3 Registration and Server Verification

| ID | Requirement |
|---|---|
| CVU-20 | Registration **shall** require a non-blank `fileName` (max 255) and a non-blank `publicId` (max 255). |
| CVU-21 | If `candidateId` is supplied and does not equal the authenticated Candidate's profile id, the system **shall** return `40300`. |
| CVU-22 | The `publicId` **shall** start with `candidates/{candidateId}/cvs/`; otherwise the system **shall** return `40023`. |
| CVU-23 | The system **shall** read the asset from Cloudinary. A missing asset or a storage provider that cannot confirm it **shall** return `40023` or `50300` respectively. |
| CVU-24 | The stored asset **shall** have format PDF, DOC, or DOCX and a content type that maps exactly to that format. Otherwise the system **shall** return `40021`. |
| CVU-25 | The stored asset byte size **shall** be greater than 0 and at most 10 MB. Otherwise the system **shall** return `40021`. |
| CVU-26 | The supplied `fileName` **shall** have an extension matching the verified asset format and **shall** contain no path separators or control characters. Otherwise the system **shall** return `40021`. |
| CVU-27 | The system **shall** ignore client-supplied `fileSize` and `contentType` for trust decisions and use the values read from Cloudinary. When supplied for backward compatibility, `fileSize` **shall** be positive; it is still not a verification source. |
| CVU-28 | The system **shall** reject a `publicId` that is already registered with `40902` and **shall not** delete the existing asset. |
| CVU-29 | The system **shall** serialize quota/default decisions on the Candidate profile row, re-check the quota, and reject a sixth CV with `40022`. |
| CVU-30 | The first registered CV **shall** have `isDefault = true`; later CVs **shall** have `isDefault = false`. |
| CVU-31 | On validation, quota, or unexpected registration failure, the system **shall** delete the just-uploaded asset on a best-effort basis. Cleanup failure **shall not** mask the original error. |
| CVU-32 | On success, the system **shall** return `201 Created` with the registered CV metadata. |
| CVU-33 | A registration request **shall** create at most one `candidate_cvs` row. |

### 4.4 Listing and Download

| ID | Requirement |
|---|---|
| CVU-40 | `GET /api/v1/candidate/cvs` **shall** return only the authenticated Candidate's CVs, ordered by `createdAt` descending. |
| CVU-41 | If a legacy `candidateId` query parameter is supplied and does not equal the authenticated Candidate's profile id, the system **shall** return `40300`. |
| CVU-42 | `GET /api/v1/candidate/cvs/{id}/download-url` **shall** return a signed URL only when the CV belongs to the authenticated Candidate. Otherwise the system **shall** return `40405`. |
| CVU-43 | The download URL **shall** expire after 15 minutes. |
| CVU-44 | The download response **shall** include the URL and the Cloudinary public id; signature-only fields may be `null`. |

---

## 5. Flow Diagrams

### 5.1 Upload and Register

```text
Candidate          Browser                GuardWork API            Cloudinary          PostgreSQL
   |                  |                         |                      |                    |
   |-- select file -->|                         |                      |                    |
   |                  |-- validate type/size -> |                      |                    |
   |-- consent ------>|                         |                      |                    |
   |                  |-- POST /upload-url ---->|                      |                    |
   |                  |                         |-- count CVs ----------------------------->|
   |                  |                         |<-- count --------------------------------|
   |                  |                         |-- sign public_id --> |                    |
   |                  |<-- signature -----------|                      |                    |
   |                  |-- multipart upload --------------------------->|                    |
   |                  |<-- upload result ------------------------------|                    |
   |                  |-- POST /api/v1/candidate/cvs -->|                 |                    |
   |                  |                         |-- read asset ------->|                    |
   |                  |                         |<-- metadata ---------|                    |
   |                  |                         |-- validate asset     |                    |
   |                  |                         |-- lock profile ----->|                    |
   |                  |                         |-- insert CV ----------------------------->|
   |                  |<-- 201 envelope --------|                      |                    |
   |<-- success ------|                         |                      |                    |
```

### 5.2 Registration Decision Tree

```text
POST /api/v1/candidate/cvs
  |
  +-- field validation failed --------------------> 400
  +-- candidateId mismatch ----------------------> 40300
  +-- publicId outside own folder ---------------> 40023
  +-- asset not found / unverifiable ------------> 40023
  +-- storage provider unavailable ---------------> 50300
  +-- unsupported format / type / size -----------> 40021 (delete asset)
  +-- filename extension mismatch ----------------> 40021 (delete asset)
  +-- publicId already registered ----------------> 40902 (keep asset)
  +-- quota already reached ----------------------> 40022 (delete asset)
  +-- otherwise ----------------------------------> insert row, 201
```

---

## 6. Business Rules

| ID | Rule |
|---|---|
| BR-CV-01 | A Candidate may have at most five registered CVs. |
| BR-CV-02 | Only PDF, DOC, and DOCX files up to 10 MB are accepted. |
| BR-CV-03 | The first registered CV is the default CV. |
| BR-CV-04 | At most one CV per Candidate may be default. |
| BR-CV-05 | A CV belongs to exactly one Candidate and cannot be listed or downloaded by another Candidate. |
| BR-CV-06 | Cloudinary `public_id` values are unique across the table. |
| BR-CV-07 | All storage keys for a Candidate's CVs live under `candidates/{candidateId}/cvs/`. |
| BR-CV-08 | Client-supplied folder, public id, size, and content type are not trusted. |
| BR-CV-09 | A failed registration must not leave a database row; storage cleanup is best-effort. |
| BR-CV-10 | Deleting a library CV later must not modify an application snapshot; this specification does not implement deletion. |
| BR-CV-11 | Recruiter access to a CV is granted through the application snapshot workflow, not by guessing a CV id. |
| BR-CV-12 | CV metadata is personal data. Logs must not include file bytes, raw CV text, or signed URLs. |

---

## 7. Error Catalogue

| Error code | HTTP | Constant | Trigger |
|---|---|---|---|
| `40000` | 400 | `INVALID_INPUT` | Generic bean-validation failure (missing `fileName`, missing `publicId`, oversized fields). |
| `40020` | 400 | `CV_CONSENT_REQUIRED` | Legacy/optional code; no longer returned by the current backend because consent is handled by the frontend. |
| `40021` | 400 | `INVALID_CV_FILE_FORMAT` | Unsupported format, mismatched content type, filename mismatch, empty file, or file over 10 MB. |
| `40022` | 400 | `CV_QUOTA_EXCEEDED` | Candidate already has five registered CVs. |
| `40023` | 400 | `CV_ASSET_NOT_VERIFIED` | Asset is missing, outside the Candidate's folder, or cannot be verified. |
| `40100` | 401 | `UNAUTHENTICATED` | Missing or invalid Bearer token. |
| `40300` | 403 | `FORBIDDEN` | `candidateId` does not match the authenticated Candidate. |
| `40401` | 404 | `USER_NOT_FOUND` | The authenticated account no longer exists. |
| `40405` | 404 | `CV_NOT_FOUND` | CV does not exist or belongs to another Candidate (download). |
| `40902` | 409 | `DUPLICATE_CV` | The Cloudinary public id is already registered. |
| `50300` | 503 | `CV_STORAGE_UNAVAILABLE` | Cloudinary is unavailable or a storage operation fails. |
| — | 500 | — | Unexpected server failure. |

---

## 8. API Specification

### Conventions

- **Base URL:** `http://localhost:8080`
- **Auth:** `Authorization: Bearer <jwt>`
- **Content-Type:** `application/json` for GuardWork endpoints; Cloudinary uploads use
  `multipart/form-data`.
- **CV id:** UUID
- **Success envelope:** `ApiResponse`
- **Error envelope:** `ErrorApiResponse`

---

### 8.1 `POST /api/v1/candidate/cvs/upload-url` — Request signed upload target

Validates consent and quota, then returns a Cloudinary signature bound to a server-generated
`public_id`.

**Request Body**

```json
{}
```

Consent is collected by the frontend. `consentGiven`, `candidateId`, `folder`, and `publicId` are all
optional and ignored by the backend; `folder` and `publicId` are never used for storage placement.

**Validation**

No backend validation is performed for consent. `folder` and `publicId` are ignored.

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Success",
  "data": {
    "url": "https://api.cloudinary.com/v1_1/demo/auto/upload",
    "apiKey": "123456789012345",
    "timestamp": "1761811200",
    "signature": "d3f1...",
    "publicId": "candidates/e7b1c2a4-3f5d-4a6e-8b7c-1d2e3f4a5b6c/cvs/0f5f0a36-5d1f-4a5d-9f49-6e4a8f0c7b11"
  }
}
```

**Cloudinary upload**

The browser posts to `data.url` as `multipart/form-data` with:

| Field | Value |
|---|---|
| `file` | The selected PDF/DOC/DOCX file. |
| `api_key` | `data.apiKey` |
| `timestamp` | `data.timestamp` |
| `signature` | `data.signature` |
| `public_id` | `data.publicId` |

The browser must not add an unsigned `folder`, `public_id`, or `upload_preset`. The Cloudinary
response supplies the verified `public_id`, `format`, `bytes`, and `resource_type`.

**Error Responses**

| Condition | Error code | HTTP |
|---|---|---|
| Consent missing/false | `40020` | 400 |
| Quota reached | `40022` | 400 |
| Missing/invalid token | `40100` | 401 |
| Storage provider unavailable | `50300` | 503 |

---

### 8.2 `POST /api/v1/candidate/cvs` — Register uploaded CV

Verifies the Cloudinary asset and persists its metadata.

**Request Body**

```json
{
  "fileName": "Nguyen_Van_An_CV.pdf",
  "publicId": "candidates/e7b1c2a4-3f5d-4a6e-8b7c-1d2e3f4a5b6c/cvs/0f5f0a36-5d1f-4a5d-9f49-6e4a8f0c7b11"
}
```

`candidateId` is optional and, if supplied, must match the authenticated Candidate.
`fileSize` and `contentType` may be sent by older clients but are ignored for verification.

**Validation**

| Field | Rule |
|---|---|
| `fileName` | Required, non-blank, max 255; extension must match the stored asset format; no path separators/control characters. |
| `publicId` | Required, non-blank, max 255; must start with the authenticated Candidate's folder. |
| `candidateId` | Optional; must equal the authenticated Candidate's profile id. |

**Response `201 Created`**

```json
{
  "status": 201,
  "message": "CV uploaded successfully",
  "data": {
    "id": "c3d4e5f6-a7b8-4c9d-8e0f-1a2b3c4d5e6f",
    "candidateId": "e7b1c2a4-3f5d-4a6e-8b7c-1d2e3f4a5b6c",
    "fileName": "Nguyen_Van_An_CV.pdf",
    "fileSize": 204800,
    "contentType": "application/pdf",
    "publicId": "candidates/e7b1c2a4-3f5d-4a6e-8b7c-1d2e3f4a5b6c/cvs/0f5f0a36-5d1f-4a5d-9f49-6e4a8f0c7b11",
    "isDefault": true,
    "createdAt": "2026-10-31T10:00:00Z",
    "updatedAt": "2026-10-31T10:00:00Z"
  }
}
```

**Error Responses**

| Condition | Error code | HTTP |
|---|---|---|
| Missing/blank field | `40000` | 400 |
| Unsupported format, size, or filename mismatch | `40021` | 400 |
| Quota reached | `40022` | 400 |
| Asset not verified | `40023` | 400 |
| Candidate id mismatch | `40300` | 403 |
| Duplicate public id | `40902` | 409 |
| Storage unavailable | `50300` | 503 |

---

### 8.3 `GET /api/v1/candidate/cvs` — List own CVs

Returns the authenticated Candidate's CVs, newest first. The result is a plain array inside `data`,
not a paginated wrapper.

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Success",
  "data": [
    {
      "id": "c3d4e5f6-a7b8-4c9d-8e0f-1a2b3c4d5e6f",
      "candidateId": "e7b1c2a4-3f5d-4a6e-8b7c-1d2e3f4a5b6c",
      "fileName": "Nguyen_Van_An_CV.pdf",
      "fileSize": 204800,
      "contentType": "application/pdf",
      "publicId": "candidates/e7b1c2a4-3f5d-4a6e-8b7c-1d2e3f4a5b6c/cvs/0f5f0a36-5d1f-4a5d-9f49-6e4a8f0c7b11",
      "isDefault": true,
      "createdAt": "2026-10-31T10:00:00Z",
      "updatedAt": "2026-10-31T10:00:00Z"
    }
  ]
}
```

**Error Responses**

| Condition | Error code | HTTP |
|---|---|---|
| `candidateId` does not match | `40300` | 403 |
| Missing/invalid token | `40100` | 401 |

---

### 8.4 `GET /api/v1/candidate/cvs/{id}/download-url` — Issue signed download URL

Returns a temporary URL for the owner's CV.

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Success",
  "data": {
    "url": "https://res.cloudinary.com/demo/raw/upload/...signature...",
    "apiKey": null,
    "timestamp": null,
    "signature": null,
    "publicId": "candidates/e7b1c2a4-3f5d-4a6e-8b7c-1d2e3f4a5b6c/cvs/0f5f0a36-5d1f-4a5d-9f49-6e4a8f0c7b11"
  }
}
```

The URL expires after 15 minutes. The client should open it in a new tab or trigger a download; it
must not cache the URL as a permanent CV link.

**Error Responses**

| Condition | Error code | HTTP |
|---|---|---|
| CV missing or owned by another Candidate | `40405` | 404 |
| Missing/invalid token | `40100` | 401 |
| Storage unavailable | `50300` | 503 |

---

## 9. Infrastructure Architecture

### 9.1 Storage Boundary

```text
CandidateCvStorage (interface)
   |-- createUploadSignature(publicId)
   |-- find(publicId) -> StoredCvAsset
   |-- delete(publicId, resourceType)
   |-- copy(source, target, resourceType, format)
   |-- createDownloadUrl(publicId, resourceType, format, ttl)
             |
             v
CloudinaryCandidateCvStorage
```

Cloudinary is wrapped behind an interface so the CV service can enforce ownership and validation
without calling Cloudinary in unit tests.

### 9.2 Relational Schema

```text
candidate_profiles (1) ─── (0:many) ─── candidate_cvs
        ON DELETE CASCADE
```

Application snapshots do not reference `candidate_cvs` with a foreign key; the submitted copy is
independent by design.

### 9.3 Cloudinary Configuration

| Property | Purpose | Source |
|---|---|---|
| `cloudinary.cloud_name` | Cloud name used in the upload/download URL. | Environment-backed configuration |
| `cloudinary.api_key` | Public API key returned to the browser. | Environment-backed configuration |
| `cloudinary.api_secret` | Server-only signing secret. Never returned to the browser. | Environment-backed configuration |

No raw public CDN URL is exposed by GuardWork. File access uses signed URLs with a 15-minute expiry.

### 9.4 Constants

| Constant | Value | Source | Notes |
|---|---|---|---|
| `MAX_SIZE_BYTES` | `10,485,760` | `CandidateCvService` | 10 MB. |
| `MAX_CVS_PER_CANDIDATE` | `5` | `CandidateCvService` | Quota. |
| `DOWNLOAD_URL_TTL` | `15 minutes` | `CandidateCvService` | Signed download lifetime. |
| Allowed formats | `pdf`, `doc`, `docx` | `CandidateCvService` | Extension/format allowlist. |
| Candidate folder | `candidates/{candidateId}/cvs/` | `CandidateCvService` | Server-owned prefix. |

### 9.5 Migrations

| Migration | Purpose |
|---|---|
| `V2026.10.15.63__create_candidate_cvs_table.sql` | Creates `candidate_cvs`. |
| `V2026.10.30.81__secure_candidate_cv_integrity.sql` | Adds owner FK, unique public id, one-default index, and candidate/date index. |
| `V2026.10.31.90__make_candidate_cv_timestamps_timestamptz.sql` | Converts CV timestamps to `TIMESTAMPTZ` as UTC. |

---

## 10. Non-Functional Requirements

| ID | Category | Requirement |
|---|---|---|
| NFR-CV-01 | Security | CV ownership **shall** come from the authenticated principal; client-supplied owner ids **shall** be ignored or rejected. |
| NFR-CV-02 | Security | `api_secret` **shall** never be returned to the browser or logged. |
| NFR-CV-03 | Privacy | CV files **shall** be accessible only through short-lived signed URLs. |
| NFR-CV-04 | Privacy | The UI **shall** warn against including national ID, bank account, or password data before upload. |
| NFR-CV-05 | Integrity | A registered CV row **shall** correspond to a verified Cloudinary asset. |
| NFR-CV-06 | Reliability | Registration failures **shall** attempt storage cleanup without masking the original error. |
| NFR-CV-07 | Concurrency | Concurrent registrations **shall** not exceed the five-CV quota or create two default CVs. |
| NFR-CV-08 | Performance | Signature issuance and CV listing **shall** complete within 300 ms at p95, excluding Cloudinary round-trip time. |
| NFR-CV-09 | Observability | Storage failures **shall** be logged with an event name and resource type, never with file bytes or signed URLs. |
| NFR-CV-10 | Compatibility | Existing endpoint paths and response fields **shall** remain backward compatible while the success envelope is standardized. |

---

## 11. Implementation Alignment and Open Gaps

The current backend implements the two-step flow, consent, ownership, canonical storage prefix,
server-side asset verification, quota, first-CV default, unique public id, and owner-only download.
The following gaps remain:

| ID | Difference | Disposition |
|---|---|---|
| CV-GAP-01 | Resolved: all four (plus delete/default) CV endpoints return the standard success envelope and are committed. | No further work required. |
| CV-GAP-02 | No server-side idempotency key or rate limiter for double-click/retry. Duplicate `publicId` catches a repeated registration, but a double-click that requests two signatures creates two distinct public ids. | The frontend disables the submit button; add an approved idempotency/rate-limit mechanism for EC-CAN-CV-05. |
| CV-GAP-03 | Consent is checked but no consent evidence is persisted. | Add an approved consent-event record if the privacy requirement needs auditable proof. |
| CV-GAP-04 | A client that uploads to Cloudinary but never calls registration leaves an orphan. | Add a scheduled orphan-cleanup task in a separate approved scope. |
| CV-GAP-05 | Security configuration requires authentication but does not enforce the Candidate role on `/api/v1/candidate/cvs/**`. | Add an approved role/authorization guard. |
| CV-GAP-06 | Resolved by `V2026.10.31.90__make_candidate_cv_timestamps_timestamptz.sql`, which converts `created_at` and `updated_at` to `TIMESTAMPTZ` as UTC. The JPA entity keeps `java.time.Instant`. | No further work required. |
| CV-GAP-07 | Resolved for CV management: backend now exposes `DELETE /api/v1/candidate/cvs/{id}` and `PATCH /api/v1/candidate/cvs/{id}/default`; the storage object is deleted before the database row, and quota/default state is updated. | Keep CV lifecycle behavior aligned with UC-CAN-05 and UC-CAN-06. |
| CV-GAP-08 | The server verifies Cloudinary's detected format/content type but does not inspect magic bytes itself. | Accept Cloudinary metadata as the verification source for this scope; add direct byte inspection only through a separate approved security design. |
| CV-GAP-09 | No file-content scan for sensitive data in the CV. | Warn the user and defer OCR/content scanning. |
| CV-GAP-10 | Resolved by product decision: consent is no longer validated or enforced by the backend; the frontend owns the confirmation flow. The backend accepts `consentGiven` optionally for compatibility only. | No further backend work required. |
| CV-GAP-11 | Bean validation returns `40000` through the shared handler for CV field validation such as a blank `fileName` or `publicId`. | Keep as current shared handler behavior; add assertions only if stricter CV-specific codes are needed. |

---

## 12. Verification

Applicable checks:

- `CandidateCvControllerTest` — endpoint status, validation, and response mapping.
- `CandidateCvServiceTest` — consent, quota, folder derivation, ownership, asset verification,
  duplicate handling, first-default behavior, cleanup, list scoping, and download authorization.
- `JobApplicationHttpTest` — source CV ownership and application snapshot copy behavior.
- Manual check against Cloudinary in a non-production environment for signed upload, asset readback,
  and download URL expiry.
- Migration checks for the owner FK, unique public id, and one-default partial index.

The controller tests already assert the `ApiResponse` shape for all four CV endpoints. Confirm those
assertions remain after the uncommitted envelope change is committed.

---

## 13. Approval Record

| Date | Actor | Decision |
|---|---|---|
| 2026-10-31 | User | Confirmed standard success envelope, server-owned Cloudinary folder, and the two-step direct-upload contract. |
| Pending | chiko | Formal approval of this specification. |
