# Candidate Profile Update — Software Requirements Specification

| Field | Value |
|---|---|
| **Specification ID** | GW-SR-CAND-01 |
| **Project** | GuardWork |
| **Module** | Candidate Profile |
| **File** | `candidate-profile-update-spec.md` |
| **Version** | 0.1.0 |
| **Date** | 2026-10-31 |
| **Last Updated** | 2026-10-31 |
| **Owner** | GuardWork thesis team |
| **Approver** | chiko |
| **Status** | Draft — approval pending |
| **Related use case** | UC-CAN-01 — Cập nhật hồ sơ cá nhân |
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

This specification defines the candidate profile read and update behavior for GuardWork. It is the
source of truth for the profile screen contract, validation rules, account-contact ownership, API
payloads, and persistence constraints. It refines UC-CAN-01 in
[`docs/feat/candidates/usecase.md`](../../feat/candidates/usecase.md).

### 1.2 Scope

| Sub-feature | Responsibility |
|---|---|
| **Profile retrieval** | Return the authenticated Candidate's profile, creating a skeleton profile from account data when none exists. |
| **Profile update** | Update full name, phone number, avatar URL, skills, experience, education, and public-visibility flag. |
| **Account contact display** | Return account email and canonical phone number as read-only or account-derived values. |
| **Validation and normalization** | Enforce field limits, Vietnamese mobile format, skill normalization, and structured history rules. |
| **Persistence** | Store structured profile data in `candidate_profiles` and the canonical phone number on `users`. |

Out of scope:

- changing the account email or login credentials through the profile API;
- uploading or cropping avatar binary data (the profile stores only an HTTPS URL);
- CV management (see [`candidate-cv-upload-spec.md`](candidate-cv-upload-spec.md));
- candidate onboarding, account suspension, recruiter visibility, or talent-pool search;
- profile deletion and data-erasure workflows.

### 1.3 Decisions confirmed for this specification

| Decision | Value |
|---|---|
| Full-name rule | Allow internal spaces. Reject only blank values; maximum 150 characters. The earlier UC-CAN-01 “no spaces” wording was an error. |
| Email ownership | Account email is read-only on the profile. It is displayed from `users.email` and is not accepted by the profile update API. |
| Phone ownership | `users.phone_number` is authoritative. Input may be `0xxxxxxxxx` or `+84xxxxxxxxx`; storage and API output use canonical `+84`. The UI displays `0xxxxxxxxx`. |
| Profile update style | The client sends the editable profile form. Required fields are replaced; optional fields preserve the current value when omitted/`null`. Lists are replaced when present and cleared with `[]`. |
| Experience/education optional fields | The identifying fields are required. `endDate`/`endYear` and `description` are optional because a candidate may still be employed or studying. |
| API success shape | All profile endpoints return the standard `ApiResponse` envelope. |

---

## 2. Overall Description

### 2.1 High-Level Flow

```text
Candidate
   |
   | GET /api/v1/candidates/profile
   v
CandidateProfileController
   |
   | SecurityUtils.getCurrentUserId()
   v
CandidateProfileService
   |-- find profile by user id
   |-- if missing: create skeleton from users.first_name + users.last_name
   |-- attach read-only email and canonical phone from users
   v
CandidateProfile + users
   |
   v
ApiResponse<CandidateProfileResponse>
```

Update flow:

```text
Browser form
   |
   | PUT /api/v1/candidates/profile
   v
Bean validation (@Valid)
   |
   v
CandidateProfileService.updateProfile(userId, request)
   |-- lock users row
   |-- validate and normalize phone
   |-- create skeleton if needed
   |-- update requested fields
   |-- normalize skills
   |-- flush phone uniqueness check
   |-- save profile
   v
ApiResponse<CandidateProfileResponse>
```

### 2.2 Lifecycle

A Candidate has at most one profile, keyed by `user_id`. The profile is created lazily and has no
business status machine.

```text
(no candidate_profiles row)
        |
        | GET /profile or PUT /profile
        v
SKELETON
  full_name = account first + last name
  skills = []
  experience = []
  education = []
  is_public = true
        |
        | successful update
        v
EXISTING_PROFILE
        |
        | repeated updates
        +--> EXISTING_PROFILE
```

### 2.3 Response Envelope

Successful responses use the project `ApiResponse` envelope:

```json
{
  "status": 200,
  "message": "Success",
  "data": { }
}
```

Error responses use the shared `ErrorApiResponse` shape. `status` carries the application error
code, not the HTTP status:

```json
{
  "message": "A Vietnamese mobile number is required",
  "status": 40030,
  "detailed_message": "Optional diagnostic detail",
  "errors": {
    "phoneNumber": "A Vietnamese mobile number is required"
  }
}
```

`errors` is present for field-level validation failures. Clients must branch on the HTTP status and
the application error code, never on the human-readable message.

### 2.4 Public vs Protected Endpoints

Both profile endpoints are protected:

- `Authorization: Bearer <jwt>` is required.
- The Candidate identity comes from the authenticated principal, never from the request body or a
  query parameter.
- The endpoint is intended for an authenticated Candidate account. A Staff or employer account must
  not create or update a Candidate profile through this API.
- Missing or invalid authentication returns `401`.

---

## 3. Data Models

### 3.1 `candidate_profiles` (PostgreSQL)

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | PK, default `gen_random_uuid()` | Profile identity. |
| `user_id` | `BIGINT` | NOT NULL, FK → `users(id)` ON DELETE CASCADE, unique | One profile per account. |
| `full_name` | `VARCHAR(150)` | NOT NULL | Trimmed; internal spaces allowed. |
| `avatar_url` | `VARCHAR(512)` | Nullable | HTTPS URL only. |
| `phone_number` | `VARCHAR(20)` | Nullable, read-only legacy column | Current phone is derived from `users.phone_number`; do not write this column. |
| `skills` | `JSONB` | NOT NULL, default `[]` | Array of unique skill strings. |
| `experience` | `JSONB` | NOT NULL, default `[]` | Array of experience objects. |
| `education` | `JSONB` | NOT NULL, default `[]` | Array of education objects. |
| `experience_summary` | `TEXT` | Nullable, legacy | Retained for migration; not exposed by the API. |
| `education_summary` | `TEXT` | Nullable, legacy | Retained for migration; not exposed by the API. |
| `is_public` | `BOOLEAN` | NOT NULL, default `true` | Candidate-controlled visibility flag. |
| `created_at` | `TIMESTAMPTZ` | NOT NULL, default `now()` | Creation instant. |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL, default `now()` | Updated by the application on each write. |

There is deliberately no `email` column. `candidate_profiles.email` was removed so that
`users.email` remains the single source of truth.

### 3.2 Account contact fields (`users`)

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `BIGINT` | PK | Account identity; referenced by `candidate_profiles.user_id`. |
| `email` | `VARCHAR(255)` | NOT NULL, unique | Read-only in the profile response. |
| `phone_number` | `VARCHAR(20)` | Unique, nullable | Canonical Vietnamese mobile number, stored as `+84xxxxxxxxx`. |
| `status` | `VARCHAR(30)` | NOT NULL | Account status; only `ACTIVE` accounts may use profile APIs. |

### 3.3 Structured JSON fields

`skills`:

```json
["Java", "Spring Boot", "PostgreSQL"]
```

`experience`:

```json
[
  {
    "company": "Công ty Ánh Dương",
    "role": "Backend Engineer",
    "startDate": "2022-03",
    "endDate": "2024-06",
    "description": "Xây dựng API và tích hợp thanh toán."
  }
]
```

`education`:

```json
[
  {
    "institution": "Đại học Bách khoa",
    "degree": "Kỹ sư Công nghệ thông tin",
    "startYear": "2018",
    "endYear": "2022",
    "description": "Chuyên ngành Hệ thống thông tin."
  }
]
```

`endDate`, `endYear`, and `description` are optional. A missing end value means “current”. The
current implementation does not enforce a calendar format for the start/end strings; clients in the current implementation send `YYYY-MM` for both experience and education
start/end values.

### 3.4 Database Indexes and Constraints

| Index / constraint | Table | Column(s) | Purpose |
|---|---|---|---|
| `uk_candidate_profiles_user_id` | `candidate_profiles` | `user_id` | One profile per account. |
| `idx_candidate_profiles_user_id` | `candidate_profiles` | `user_id` | Profile lookup. |
| `idx_candidate_profiles_is_public` | `candidate_profiles` | `is_public` | Future visibility filtering. |
| `uq_users_phone_number` | `users` | `phone_number` | Prevents two accounts claiming the same mobile number. |

---

## 4. Functional Requirements

### 4.1 Profile Retrieval

| ID | Requirement |
|---|---|
| PROF-01 | The system **shall** derive the account identity from the authenticated principal and **shall not** accept a caller-supplied `userId` or `candidateId`. |
| PROF-02 | If no profile exists for the account, the system **shall** create a skeleton profile whose `full_name` is the trimmed concatenation of the account first name and last name. |
| PROF-03 | The system **shall** return `email` from `users.email` and `phoneNumber` from `users.phone_number`; it **shall not** persist or return a profile-local email. |
| PROF-04 | The system **shall** return the profile identity, account linkage, avatar URL, skills, experience, education, visibility flag, and timestamps. |
| PROF-05 | If the authenticated account no longer exists, the system **shall** reject the request with `USER_NOT_FOUND`. |
| PROF-06 | Profile retrieval **shall** not modify the profile except for the one-time skeleton creation. |

### 4.2 Profile Update

| ID | Requirement |
|---|---|
| PROF-10 | The system **shall** update only the profile belonging to the authenticated account. |
| PROF-11 | `fullName` **shall** be required, trimmed, non-blank, and at most 150 characters. Internal spaces **shall** be allowed. |
| PROF-12 | `phoneNumber` **shall** be optional. When supplied, it **shall** be a Vietnamese mobile number starting with `0` or `+84`, followed by `3`, `5`, `7`, `8`, or `9`, then eight digits; it **shall** be normalized to `+84` plus nine digits and be unique across accounts. |
| PROF-13 | When `phoneNumber` is omitted or `null`, the system **shall** preserve the current phone number. |
| PROF-14 | `avatarUrl` **shall** be optional, at most 512 characters, and **shall** start with `https://`. When omitted or `null`, the current avatar **shall** be preserved. |
| PROF-15 | `skills` **shall** contain at most 20 items. Each item **shall** be 1–30 characters after trimming. Blank items **shall** be removed. Duplicates **shall** be removed case-insensitively while preserving the first spelling. |
| PROF-16 | When `skills` is `null` or omitted, the current skills **shall** be preserved. An empty array **shall** clear the list. |
| PROF-17 | When `experience` is present, it **shall** replace the current experience list. `null` **shall** preserve the list; `[]` **shall** clear it. |
| PROF-18 | Each experience entry **shall** require non-blank `company`, `role`, and `startDate`. `company` and `role` **shall** be at most 100 characters. `description` **shall** be at most 1,000 characters. `endDate` and `description` are optional. |
| PROF-19 | When `education` is present, it **shall** replace the current education list. `null` **shall** preserve the list; `[]` **shall** clear it. |
| PROF-20 | Each education entry **shall** require non-blank `institution`, `degree`, and `startYear`. `institution` **shall** be at most 150 characters; `degree` **shall** be at most 100 characters; `description` **shall** be at most 1,000 characters. `endYear` and `description` are optional. |
| PROF-21 | `isPublic` is optional. When supplied, it **shall** replace the current visibility flag; when `null`, the flag **shall** be preserved. |
| PROF-22 | The system **shall** update the profile and canonical phone number in one transaction. A phone-number conflict **shall** roll back both writes. |
| PROF-23 | The system **shall** ignore or reject any `email`, `candidateId`, `userId`, or `id` supplied in the update payload; it **shall never** use such values to select or rewrite the account identity. |
| PROF-24 | On success, the system **shall** return the complete updated profile using the standard success envelope. |
| PROF-25 | The system **shall** update `updated_at` on every successful profile write. |

---

## 5. Flow Diagrams

### 5.1 Update Profile

```text
Client                     Controller              Service                  PostgreSQL
  |                            |                      |                         |
  |-- PUT /profile ----------->|                      |                         |
  |   Bearer JWT               |-- current user id -->|                         |
  |                            |                      |-- lock users row ------>|
  |                            |                      |<-- user ----------------|
  |                            |                      |-- find profile -------->|
  |                            |                      |<-- profile / empty -----|
  |                            |                      |-- create skeleton ----->| (if missing)
  |                            |                      |-- validate fields      |
  |                            |                      |-- normalize phone      |
  |                            |                      |-- normalize skills     |
  |                            |                      |-- flush users phone --->|
  |                            |                      |<-- unique / conflict ---|
  |                            |                      |-- save profile -------->|
  |                            |<-- profile ----------|                         |
  |<-- 200 ApiResponse --------|                      |                         |
```

### 5.2 Optional Field Decision Tree

```text
Incoming field
  |
  +-- fullName absent or blank ----------> 400 validation error
  |
  +-- phoneNumber null/absent -----------> preserve current phone
  |     |
  |     +-- present but invalid ---------> 400 INVALID_PHONE_NUMBER
  |     +-- present and already claimed --> 409 IDENTIFIER_UNAVAILABLE
  |
  +-- avatarUrl null/absent -------------> preserve current avatar
  |     +-- present but not HTTPS -------> 400 validation error
  |
  +-- skills null/absent ----------------> preserve
  |     +-- [] ---------------------------> clear
  |     +-- non-empty list ---------------> trim, dedupe, validate, replace
  |
  +-- experience null/absent ------------> preserve
  |     +-- [] ---------------------------> clear
  |     +-- entries ----------------------> validate and replace
  |
  +-- education null/absent -------------> preserve
  |     +-- [] ---------------------------> clear
  |     +-- entries ----------------------> validate and replace
  |
  +-- isPublic null/absent --------------> preserve
        +-- true / false -----------------> replace
```

---

## 6. Business Rules

| ID | Rule |
|---|---|
| BR-CAND-01 | An account has exactly one Candidate profile and cannot select another account's profile. |
| BR-CAND-02 | `users.email` is the single source of truth for email; it is read-only on the profile. |
| BR-CAND-03 | `users.phone_number` is the single source of truth for phone; the profile response is a projection of it. |
| BR-CAND-04 | Phone numbers are stored in canonical `+84xxxxxxxxx` form and displayed as `0xxxxxxxxx` by the client. |
| BR-CAND-05 | A phone number can belong to only one account. |
| BR-CAND-06 | Skills are a set-like list: order is preserved, blank entries are dropped, and duplicate spellings are collapsed case-insensitively. |
| BR-CAND-07 | Experience and education are replace-in-full lists when supplied; `null` means “no change”, `[]` means “clear”. |
| BR-CAND-08 | A profile update must not create, delete, or modify CV records or applications. |
| BR-CAND-09 | The profile is private data. It may be exposed to other actors only through a separately approved workflow and projection. |
| BR-CAND-10 | The current job/education of a candidate may omit an end value. |

---

## 7. Error Catalogue

| Error code | HTTP | Constant | Trigger |
|---|---|---|---|
| `40000` | 400 | `INVALID_INPUT` | Generic service validation failure, including invalid skill count/length. |
| `40030` | 400 | `INVALID_PHONE_NUMBER` | Phone is present but is not a valid Vietnamese mobile number. |
| `40100` | 401 | `UNAUTHENTICATED` | Missing, malformed, or expired Bearer token. |
| `40300` | 403 | `FORBIDDEN` | Authenticated account is not permitted to use the Candidate profile API. |
| `40401` | 404 | `USER_NOT_FOUND` | The authenticated account no longer exists. |
| `40930` | 409 | `IDENTIFIER_UNAVAILABLE` | The requested phone number is already used by another account. |
| — | 500 | — | Unexpected server failure; clients show a generic retry message. |

Field-level validation errors use HTTP `400` with an `errors` map keyed by field name. The message
is user-appropriate but not stable enough for programmatic branching.

---

## 8. API Specification

### Conventions

- **Base URL:** `http://localhost:8080`
- **Auth:** `Authorization: Bearer <jwt>`
- **Content-Type:** `application/json`
- **ID types:** profile `UUID`; account `BIGINT`
- **Success envelope:** `ApiResponse`
- **Error envelope:** `ErrorApiResponse`

---

### 8.1 `GET /api/v1/candidates/profile` — Read current profile

Returns the authenticated Candidate's profile. Creates a skeleton profile when one does not exist.

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Success",
  "data": {
    "id": "e7b1c2a4-3f5d-4a6e-8b7c-1d2e3f4a5b6c",
    "userId": 42,
    "email": "an@example.vn",
    "fullName": "Nguyễn Văn An",
    "avatarUrl": "https://res.cloudinary.com/demo/image/upload/avatar.jpg",
    "phoneNumber": "+84901234567",
    "skills": ["Java", "Spring Boot"],
    "experience": [
      {
        "company": "Công ty Ánh Dương",
        "role": "Backend Engineer",
        "startDate": "2022-03",
        "endDate": "2024-06",
        "description": "Xây dựng API và tích hợp thanh toán."
      }
    ],
    "education": [
      {
        "institution": "Đại học Bách khoa",
        "degree": "Kỹ sư Công nghệ thông tin",
        "startYear": "2018",
        "endYear": "2022",
        "description": "Chuyên ngành Hệ thống thông tin."
      }
    ],
    "isPublic": true,
    "createdAt": "2026-10-20T08:00:00Z",
    "updatedAt": "2026-10-31T09:30:00Z"
  }
}
```

**Error Responses**

| Condition | Error code | HTTP |
|---|---|---|
| Missing/invalid token | `40100` | 401 |
| Account not found | `40401` | 404 |
| Account lacks Candidate access | `40300` | 403 |

---

### 8.2 `PUT /api/v1/candidates/profile` — Update current profile

Updates the editable profile form. The request does not contain an email, user id, candidate id, or
profile id.

**Request Body**

```json
{
  "fullName": "Nguyễn Văn An",
  "phoneNumber": "0901234567",
  "avatarUrl": "https://res.cloudinary.com/demo/image/upload/avatar.jpg",
  "skills": ["Java", "Spring Boot"],
  "experience": [
    {
      "company": "Công ty Ánh Dương",
      "role": "Backend Engineer",
      "startDate": "2022-03",
      "endDate": "2024-06",
      "description": "Xây dựng API và tích hợp thanh toán."
    }
  ],
  "education": [
    {
      "institution": "Đại học Bách khoa",
      "degree": "Kỹ sư Công nghệ thông tin",
      "startYear": "2018",
      "endYear": "2022",
      "description": "Chuyên ngành Hệ thống thông tin."
    }
  ],
  "isPublic": true
}
```

**Validation**

| Field | Rule |
|---|---|
| `fullName` | Required, non-blank after trim, max 150; internal spaces allowed. |
| `phoneNumber` | Optional; `0xxxxxxxxx` or `+84xxxxxxxxx`; canonicalized to `+84`; unique. |
| `avatarUrl` | Optional; max 512; must match `^https://.+$`. |
| `skills` | Optional `null` preserves; max 20 items; each 1–30 after trim; blanks dropped; case-insensitive dedupe. |
| `experience` | Optional `null` preserves; entries require `company`, `role`, `startDate`; max lengths above. |
| `education` | Optional `null` preserves; entries require `institution`, `degree`, `startYear`; max lengths above. |
| `isPublic` | Optional boolean; `null` preserves. |

**Response `200 OK`**

Returns the same `CandidateProfileResponse` shape as section 8.1. `message` is `"Success"`.

**Error Responses**

| Condition | Error code | HTTP |
|---|---|---|
| Invalid field value or nested entry | `40000` | 400 |
| Invalid phone format | `40030` | 400 |
| Missing/invalid token | `40100` | 401 |
| Account not found | `40401` | 404 |
| Phone already claimed | `40930` | 409 |
| Unexpected failure | — | 500 |

---

## 9. Infrastructure Architecture

### 9.1 Persistence

- Spring Data JPA entity: `CandidateProfile`.
- Repository: `CandidateProfileRepository`.
- `skills`, `experience`, and `education` are JSONB columns mapped through Jackson converters.
- The profile and account phone are updated in one JPA transaction.
- The account row is locked with `findByIdForUpdate` before phone writes so concurrent claims
  serialize.
- `users.phone_number` has a unique constraint; a `DataIntegrityViolationException` containing
  `uq_users_phone_number` is translated to `40930`.

### 9.2 Migrations

| Migration | Purpose |
|---|---|
| `V2026.10.13.57__create_candidate_profiles_table.sql` | Creates `candidate_profiles`. |
| `V2026.10.29.79__add_candidate_experience_education.sql` | Adds structured `experience`/`education`, removes profile email. |
| `V2026.10.27.83__canonical_account_phone.sql` | Makes account phone canonical. |

Committed migrations are immutable; any schema change requires a new migration and approval.

### 9.3 Configuration Constants

| Constant | Value | Source | Notes |
|---|---|---|---|
| `MAX_SKILLS` | 20 | `ValidationConstants` | Maximum skills per profile. |
| `MAX_SKILL_LENGTH` | 30 | `ValidationConstants` | Maximum characters per skill. |
| `HTTPS_URL_REGEX` | `^https://.+$` | `ValidationConstants` | Avatar URL scheme/host check. |
| Profile max length | 150 | `users`/`candidate_profiles` schema | `fullName`. |

---

## 10. Non-Functional Requirements

| ID | Category | Requirement |
|---|---|---|
| NFR-CAND-01 | Security | The account identity **shall** come from `SecurityContext`; payload identity fields **shall** be ignored. |
| NFR-CAND-02 | Privacy | The API **shall** expose only the authenticated Candidate's profile and account contact values. |
| NFR-CAND-03 | Integrity | Profile and phone writes **shall** be atomic; a phone conflict **shall** roll back the profile change. |
| NFR-CAND-04 | Concurrency | Concurrent phone claims **shall** serialize and produce one success plus one conflict. |
| NFR-CAND-05 | Performance | A profile read or update **shall** complete within 300 ms at p95 under expected thesis-demo load, excluding network. |
| NFR-CAND-06 | Compatibility | Existing profile endpoints and response fields **shall** remain backward compatible. |
| NFR-CAND-07 | Localization | User-facing messages **shall** be Vietnamese in the UI layer; backend messages may remain English diagnostics. |
| NFR-CAND-08 | Observability | Unexpected failures **shall** be logged without logging full profile contents or phone/email values. |

---

## 11. Implementation Alignment and Open Gaps

The current backend already implements most of this contract. The following differences must be
resolved before this specification can be marked `Implemented`:

| ID | Difference | Disposition |
|---|---|---|
| PROF-GAP-01 | The CV success-envelope change is present only in the working tree: all four CV controller methods now return `ApiResponse`, while nothing is committed yet. | Track completion in `candidate-cv-upload-spec.md` (`CV-GAP-01`); profile and CV responses share the same envelope once that change is committed. |
| PROF-GAP-02 | `phoneNumber: null` currently clears the account phone instead of preserving it. | Align the service with PROF-13 before calling this spec implemented. |
| PROF-GAP-03 | `avatarUrl: null` currently clears the avatar instead of preserving it. | Align the service with PROF-14, or add an explicit removal operation. |
| PROF-GAP-04 | Profile phone validation and uniqueness errors use `ResponseStatusException` and the profile-local error handler, producing `{ status, message, data: null }` instead of `ErrorApiResponse`. The contract codes `40030` and `40930` also have no `ErrorCode` constants yet. | Add the two error codes and standardize the error body when the profile error contract is approved. |
| PROF-GAP-10 | The shared `BindException` handler omits the application code, so bean-validation failures return `ErrorApiResponse.status = 0` instead of `40000`. | Set `status` to `INVALID_INPUT` (40000) in the shared handler and add a controller-test assertion. |
| PROF-GAP-05 | Security configuration requires authentication but does not enforce the Candidate role on `/api/v1/candidates/**`. | Add an approved role/authorization guard. |
| PROF-GAP-06 | `CandidateProfileServiceTest` covers the canonical phone path but not the preserve-on-null semantics above. | Add regression tests with the implementation change. |
| PROF-GAP-07 | The legacy `experience_summary` and `education_summary` columns remain in the table. | Retain until a separate data-retirement migration is approved. |
| PROF-GAP-08 | `fullName` is stored as submitted and is not trimmed/collapsed by the service. | Normalize whitespace before saving and add a regression test. |
| PROF-GAP-09 | `@Size(min = 1)` on skill elements rejects empty strings before the service can drop them, while the contract says blank items are removed. | Decide whether blank input is dropped or rejected; align validation and tests. |

Assumption recorded for approval: UC-CAN-01’s “all experience/education fields must not be blank”
is interpreted as identity fields only; `endDate`/`endYear` and `description` are optional so a
current job or degree can be represented.

Assumption recorded for approval: UC-CAN-01’s field list is not exhaustive. Avatar URL and the
public-visibility flag are included in the update scope because the profile model already stores and
returns them; the use case was updated to name both fields.

---

## 12. Verification

Applicable checks:

- `CandidateProfileControllerTest` — response mapping, envelope, bean validation.
- `CandidateProfileServiceTest` — skeleton creation, account-derived email, skills, nested
  validation, phone canonicalization, update behavior.
- `CanonicalAccountPhoneHttpTest` — authenticated phone update and uniqueness behavior.
- Manual API check of `GET` and `PUT /api/v1/candidates/profile` against PostgreSQL.

When the gap fixes in section 11 are implemented, add tests for preserve-on-null phone/avatar,
Candidate-role authorization, and the standardized error body.

---

## 13. Approval Record

| Date | Actor | Decision |
|---|---|---|
| 2026-10-31 | User | Confirmed spaces in full name, read-only account email, canonical `+84` phone storage, and standard success envelope. |
| Pending | chiko | Formal approval of this specification. |
