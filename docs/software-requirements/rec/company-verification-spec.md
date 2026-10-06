# Company Verification & Business License Inspection — Software Requirements Specification

| Field | Value |
|---|---|
| **Specification ID** | GW-SR-REC-01 |
| **Project** | GuardWork |
| **Module** | Recruiter & Company Verification / Admin KYB Gatekeeper |
| **File** | `company-verification-spec.md` |
| **Version** | 0.1.0 |
| **Date** | 2026-10-05 |
| **Last Updated** | 2026-10-05 |
| **Owner** | VyTrg |
| **Approver** | VyTrg |
| **Status** | Approved |

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

---

## 1. Introduction

### 1.1 Purpose

This document defines the formal software requirements for the **Company Verification & Business License Inspection** feature (KYB Onboarding Gate). It formalizes the brainstorm draft `[GW-BS-REC-01]` into a verified specification governing company credential submission, administrative queue management, deterministic approval/rejection decisions with optimistic locking, append-only tamper-proof audit logging, and notification event dispatching.

### 1.2 Scope

| Sub-feature | Responsibility |
|---|---|
| **Company Verification Request** | Recruiter submission of company registration details (tax code, legal name, certificate storage URL), initializing verification status to `PENDING`. |
| **Admin Verification Queue** | Paginated, status-filtered, FIFO-sorted review queue for platform administrators (`GET /api/admin/companies/verifications`). |
| **Deterministic Verification Decision** | Administrative approval (`VERIFIED`) or rejection (`REJECTED` with mandatory 10–1000 character feedback) via `PUT /api/admin/companies/{id}/verify` with optimistic concurrency control (`expectedVersion`). |
| **Immutable Audit Logging** | Atomic append-only logging in `audit_logs` protected by database-level triggers against `UPDATE`, `DELETE`, and `TRUNCATE`. |
| **Notification Event Publishing** | Publishing asynchronous decision events to notify recruiters upon verification status transition (Deferred to subsequent phase per human approval). |

### 1.3 Technology Stack

| Layer | Technology |
|---|---|
| Runtime | Java 25 |
| Framework | Spring Boot 4.1.1 (Spring Web MVC) |
| Database | PostgreSQL 18 (Flyway migrations) |
| Persistence | Spring JDBC (`NamedParameterJdbcTemplate`) |
| API Docs | SpringDoc OpenAPI 3.1.1 (`/swagger-ui/index.html`) |

---

## 2. Overall Description

### 2.1 High-Level Flow

```text
Recruiter (Submit Info) ──> [POST /api/companies/verification-request] ──> [companies table (PENDING)]
                                                                                  │
Admin (Review Queue)   <── [GET /api/admin/companies/verifications] <────────────┘
        │
Admin (Decision)        ──> [PUT /api/admin/companies/{id}/verify]
                                 ├── Optimistic Concurrency Check (version == expectedVersion)
                                 ├── Update companies (status: VERIFIED / REJECTED, version + 1)
                                 ├── Insert audit_logs (append-only, immutable)
                                 └── Publish Notification Event (async email notification)
```

### 2.2 Lifecycle & State Machine

```text
[REGISTERED / DRAFT]
         │
         ▼ submitVerification()
     [PENDING]
      │     │
      │     └── adminReject(reason >= 10 chars) ──> [REJECTED]
      │                                                   │
      │                                                   └── resubmit() ──> [PENDING]
      └── adminApprove() ──────────────────────────> [VERIFIED]
```

### 2.3 Response Envelope

All endpoints in this module return the standard project envelope:

```json
{
  "status": 200,
  "message": "Human-readable result message",
  "data": { ... }
}
```

For paginated list responses, `data` uses the standard `PageResponse` wrapper:

```json
{
  "status": 200,
  "message": "Verification queue retrieved successfully",
  "data": {
    "content": [ ... ],
    "pageNumber": 0,
    "pageSize": 20,
    "totalElements": 42,
    "totalPages": 3,
    "first": true,
    "last": false
  }
}
```

### 2.4 Public vs Protected Endpoints

- All `/api/admin/companies/**` endpoints are protected and require administrative authorization (`role: "ADMIN"`).
- Candidate masking rules apply to unverified companies: unverified accounts cannot view unmasked candidate contact details.

---

## 3. Data Models

### 3.1 `companies` Table (PostgreSQL)

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | BIGSERIAL | PK, auto | Unique company identifier |
| `name` | VARCHAR(255) | NOT NULL | Registered company legal name |
| `tax_code` | VARCHAR(50) | NOT NULL, UNIQUE | Unique national tax registration code |
| `registration_certificate_url` | TEXT | NOT NULL | Storage URL of business license certificate |
| `verification_status` | VARCHAR(30) | NOT NULL, DEFAULT `'PENDING'` | Status: `'PENDING'`, `'VERIFIED'`, `'REJECTED'` |
| `rejection_reason` | TEXT | NULL | Actionable feedback if status is `'REJECTED'` |
| `verified_by` | BIGINT | FK -> `users(id)`, NULL | ID of Admin reviewer who made the decision |
| `verified_at` | TIMESTAMPTZ | NULL | Timestamp of decision |
| `is_banned` | BOOLEAN | NOT NULL, DEFAULT `false` | Account suspension flag |
| `version` | BIGINT | NOT NULL, DEFAULT `0` | Optimistic locking counter |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` | Entity creation timestamp |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` | Entity last modification timestamp |

### 3.2 `audit_logs` Table (PostgreSQL, Append-Only)

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | BIGSERIAL | PK, auto | Unique audit log ID |
| `admin_id` | BIGINT | NOT NULL, FK -> `users(id)` | Administrator performing action |
| `action` | VARCHAR(100) | NOT NULL | Action constant (e.g. `'COMPANY_VERIFICATION'`) |
| `target_type` | VARCHAR(50) | NOT NULL | Entity type (e.g. `'COMPANY'`) |
| `target_id` | VARCHAR(100) | NOT NULL | Identifier of target entity |
| `old_payload` | JSONB | NULL | Snapshot of state prior to change |
| `new_payload` | JSONB | NULL | Snapshot of state following change |
| `reason` | TEXT | NULL | Reason or remarks associated with action |
| `ip_address` | VARCHAR(45) | NOT NULL | Client IP address of requester |
| `user_agent` | VARCHAR(255) | NULL | HTTP User-Agent of requester |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` | Timestamp of audit entry |

### 3.3 Database Indexes & Constraints

- `companies_tax_code_key`: `UNIQUE (tax_code)`
- `companies_verification_status_check`: `CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REJECTED'))`
- `idx_companies_verification_queue`: `(verification_status, created_at)` for FIFO queue lookups.
- `idx_audit_logs_admin_created`: `(admin_id, created_at DESC)` for admin activity auditing.
- `idx_audit_logs_target`: `(target_type, target_id)` for entity audit lookups.
- PostgreSQL Immutability Trigger `trg_protect_audit_logs`: `BEFORE DELETE OR UPDATE OR TRUNCATE ON audit_logs` calling `prevent_audit_logs_mutation()` which aborts with an exception to guarantee append-only immutability.

---

## 4. Functional Requirements

| ID | Requirement | Priority |
|---|---|---|
| **REQ-REC-01** | The system shall allow recruiters to submit company verification details (name, tax code, certificate URL), creating or updating the record with status `PENDING` and version `0`. | High |
| **REQ-REC-02** | The system shall provide an administrative queue endpoint (`GET /api/admin/companies/verifications`) with pagination, FIFO sorting (`created_at ASC`), and filtering by status (`PENDING`, `VERIFIED`, `REJECTED`, or `ALL`). | High |
| **REQ-REC-03** | The system shall provide details of a company's verification dossier to authorized admins. | High |
| **REQ-REC-04** | The system shall allow admins to approve a company in `PENDING` status, updating `verification_status = 'VERIFIED'`, recording `verified_by` and `verified_at`, and incrementing `version`. | High |
| **REQ-REC-05** | The system shall allow admins to reject a company in `PENDING` status by supplying a mandatory `rejectionReason` (10–1000 characters), setting `verification_status = 'REJECTED'` and incrementing `version`. | High |
| **REQ-REC-06** | The system shall enforce optimistic concurrency control via `expectedVersion`. If `expectedVersion` does not match the current database `version`, the decision transaction shall abort with HTTP 409 Conflict (`40022 CONCURRENT_MODIFICATION`). | High |
| **REQ-REC-07** | The system shall atomically record an immutable entry into `audit_logs` capturing admin ID, IP address, user agent, old payload, new payload, and reason within the decision transaction. | High |
| **REQ-REC-08** | The system shall emit an asynchronous notification event (`CompanyVerificationEvent`) upon verification decision to notify the recruiter. | High |
| **REQ-REC-09** | The system shall allow a rejected company to update details and resubmit, transitioning status from `REJECTED` back to `PENDING`. | High |

---

## 5. Flow Diagrams

### 5.1 Admin Verification Decision Flow

```text
Admin Client              AdminController             VerificationService        Database (JDBC)        EventPublisher
     │                           │                            │                        │                      │
     │── PUT /{id}/verify ──────>│                            │                        │                      │
     │   (status, reason,        │── verifyCompany() ────────>│                        │                      │
     │    expectedVersion)       │                            │── findById(id) ───────>│                      │
     │                           │                            │<── Company entity ─────│                      │
     │                           │                            │                        │                      │
     │                           │                            │── Validate status==PENDING                     │
     │                           │                            │── Validate version==expectedVersion            │
     │                           │                            │── Validate reason if REJECTED                  │
     │                           │                            │                        │                      │
     │                           │                            │── [BEGIN TX] ─────────>│                      │
     │                           │                            │── UPDATE companies ───>│                      │
     │                           │                            │── INSERT audit_logs ──>│                      │
     │                           │                            │── [COMMIT TX] ────────>│                      │
     │                           │                            │                        │                      │
     │                           │                            │── publishEvent() ────────────────────────────>│
     │                           │<── CompanyResponse ────────│                                               │
     │<── 200 OK ────────────────│                            │                                               │
```

---

## 6. Business Rules

| ID | Rule | Description |
|---|---|---|
| **BR-REC-01** | **Eligible Verification Status** | A company profile can only be approved (`VERIFIED`) or rejected (`REJECTED`) if its current status is `PENDING`. Attempting to decide on non-pending companies returns HTTP 400 (`40010 COMPANY_NOT_PENDING`). |
| **BR-REC-02** | **Optimistic Concurrency Control** | Status mutation requires validating that `companies.version` equals `expectedVersion`. On mismatch, transaction aborts and returns HTTP 409 (`40022 CONCURRENT_MODIFICATION`). |
| **BR-REC-03** | **Mandatory Rejection Feedback** | If an admin rejects a company (`REJECTED`), `rejectionReason` must be non-blank and between 10 and 1000 characters. If approved (`VERIFIED`), `rejectionReason` must be null. |
| **BR-REC-04** | **Tax Code Uniqueness** | `tax_code` must be unique across all companies (`UNIQUE` constraint). Duplicates return HTTP 409 Conflict. |
| **BR-REC-05** | **Role-Based Admin Access** | All `/api/admin/companies/**` endpoints require admin authorization (`role: "ADMIN"`). Unauthorized requests return HTTP 401 or HTTP 403. |
| **BR-REC-06** | **Immutable Audit Trail** | Every decision atomically inserts a record into `audit_logs`. Database triggers prevent any `UPDATE`, `DELETE`, or `TRUNCATE` operations on `audit_logs`. |
| **BR-REC-07** | **Transactional Notification Delivery** | The database transaction updating company status and writing audit log must commit before or with event notification dispatch. |
| **BR-REC-08** | **Vietnamese Localization & UTF-8** | Rejection reasons, audit notes, and notifications must fully support UTF-8 Vietnamese text and `Asia/Ho_Chi_Minh` timezone. |

---

## 7. Error Catalogue

| Error Code | HTTP | Constant | Trigger Condition |
|---|---|---|---|
| `40010` | 400 | `COMPANY_NOT_PENDING` | Target company is not in `PENDING` status. |
| `40011` | 400 | `INVALID_REJECTION_REASON` | Rejection reason is missing or shorter than 10 characters. |
| `40000` | 400 | `BAD_REQUEST` | Malformed request body or invalid status value. |
| `40100` | 401 | `UNAUTHORIZED` | Caller is not authenticated. |
| `40300` | 403 | `FORBIDDEN` | Caller is not an Administrator (`role: "ADMIN"`). |
| `40400` | 404 | `COMPANY_NOT_FOUND` | Specified company ID does not exist. |
| `40022` | 409 | `CONCURRENT_MODIFICATION` | `expectedVersion` does not match current database `version`. |
| `40901` | 409 | `DUPLICATE_TAX_CODE` | Tax code is already registered by another company. |

---

## 8. API Specification

### Base URL: `/api/admin/companies`

### 8.1 Verification Queue Listing

#### `GET /api/admin/companies/verifications`

Retrieves a paginated FIFO queue of companies filtered by status.

**Query Parameters**

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `status` | String | No | `PENDING` | Filter: `PENDING`, `VERIFIED`, `REJECTED`, `ALL` |
| `page` | Integer | No | `0` | Zero-based page index |
| `size` | Integer | No | `20` | Page size (1–100) |

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Verification queue retrieved successfully",
  "data": {
    "content": [
      {
        "id": 1,
        "name": "Công ty TNHH Giải Pháp Công Nghệ Alpha",
        "taxCode": "0101234567",
        "registrationCertificateUrl": "https://storage.guardwork.vn/licenses/alpha-license.pdf",
        "verificationStatus": "PENDING",
        "rejectionReason": null,
        "verifiedBy": null,
        "verifiedAt": null,
        "version": 0,
        "createdAt": "2026-10-05T08:00:00Z"
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

---

### 8.2 Verification Decision

#### `PUT /api/admin/companies/{id}/verify`

Executes an approval or rejection decision on a pending company.

**Request Headers**
- `X-Admin-Id`: `1` (Admin user ID)
- `X-Admin-Role`: `ADMIN`

**Request Body**

```json
{
  "status": "VERIFIED",
  "rejectionReason": null,
  "expectedVersion": 0
}
```

Or for rejection:

```json
{
  "status": "REJECTED",
  "rejectionReason": "Giấy chứng nhận đăng ký kinh doanh đã hết hiệu lực hoặc bản chụp bị mờ không rõ con dấu.",
  "expectedVersion": 0
}
```

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Company verification status updated successfully",
  "data": {
    "id": 1,
    "name": "Công ty TNHH Giải Pháp Công Nghệ Alpha",
    "taxCode": "0101234567",
    "registrationCertificateUrl": "https://storage.guardwork.vn/licenses/alpha-license.pdf",
    "verificationStatus": "VERIFIED",
    "rejectionReason": null,
    "verifiedBy": 1,
    "verifiedAt": "2026-10-05T12:00:00Z",
    "version": 1,
    "createdAt": "2026-10-05T08:00:00Z"
  }
}
```

---

## 9. Infrastructure Architecture

### 9.1 Relational Schema & Persistence

Flyway Migration Script: `V2__admin_governance_and_audit.sql`

Creates:
1. `companies` table with primary key `id`, unique constraint on `tax_code`, check constraint on `verification_status`, and index `idx_companies_verification_queue`.
2. `audit_logs` table with foreign key `admin_id -> users(id)` and index `idx_audit_logs_admin_created`.
3. PostgreSQL function `prevent_audit_logs_mutation()` and trigger `trg_protect_audit_logs` enforcing append-only immutability.

### 9.2 Event Publishing

```text
Event:        CompanyVerificationEvent
Payload:      { companyId, companyName, status, rejectionReason, verifiedBy, timestamp }
Delivery:     Spring ApplicationEventPublisher (asynchronous listener)
```

---

## 10. Non-Functional Requirements

| ID | Category | Requirement |
|---|---|---|
| **NFR-ADM-01** | Concurrency | Decision operations must prevent lost updates using optimistic locking; conflicting concurrent updates must return HTTP 409 within 50ms. |
| **NFR-ADM-02** | Security & Tamper Proofing | Audit logs must be tamper-proof; any direct SQL UPDATE, DELETE, or TRUNCATE on `audit_logs` must be rejected by database triggers. |
| **NFR-ADM-03** | Vietnamese Support | All text fields, error responses, and database entries must correctly preserve UTF-8 Vietnamese diacritics. |
