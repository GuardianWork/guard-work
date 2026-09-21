# Admin Management & System Governance — Software Requirements Specification

| Field | Value |
|---|---|
| **Project** | GuardianWork |
| **Module** | Admin Management & System Governance |
| **File** | `admin_software_requirements.md` |
| **Version** | 1.0.0 |
| **Date** | 2026-09-21 |
| **Status** | Draft |

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

This document defines the software requirements for the **Admin Management & System Governance** module of GuardianWork. It translates the business workflows, technical considerations, and security constraints outlined in the system administrator role specification into engineering specifications. This document serves as the single source of truth for software engineers, QA teams, DevOps, and project stakeholders regarding system administrator capabilities, operational boundaries, database schemas, and API definitions.

### 1.2 Scope

| Sub-feature | Responsibility |
|---|---|
| **Company Verification (UC-ADM-01)** | Review submitted Business Registration Certificates and business profiles; approve or reject verification requests with feedback; trigger notification pipelines. |
| **Violation Report Resolution (UC-ADM-02)** | Moderate community violation reports filed by candidates or recruiters; resolve tickets; apply sanctions (warnings, bans); enforce optimistic concurrency to avoid race conditions. |
| **Account Ban & Suspension (UC-ADM-03)** | Suspend or reactivate user and company accounts; invalidate active session tokens immediately across distributed services via Redis blacklists. |
| **Job Posting Moderation (UC-ADM-04)** | Takedown or suspend fraudulent/violating job postings; purge associated cached documents from Redis and Elasticsearch search indices. |
| **System Health & Traffic Monitoring (UC-ADM-05)** | Expose real-time infrastructure metrics (CPU, RAM, DB connection pool) and application traffic (CCU, request rates) routed through read replicas and pre-aggregated metric buffers. |
| **Background Cron Job Management (UC-ADM-06)** | Inspect scheduled jobs, trigger ad-hoc manual runs, adjust execution frequencies, and track runtime execution logs. |
| **Audit Logging & Governance (Security)** | Capture append-only audit trail records for all state-mutating administrative actions for zero-trust compliance. |

### 1.3 Technology Stack

| Layer | Technology |
|---|---|
| Runtime | Java 25 |
| Framework | Spring Boot 4.1.1 (`spring-boot-starter-webmvc`) |
| Persistence & Migrations | PostgreSQL 16+ with Flyway (`flyway-database-postgresql`) |
| Data Access | Spring Data JDBC / Spring Data JPA & Hibernate |
| In-Memory Cache & Session Store | Redis via `StringRedisTemplate` / Redisson |
| Search & Indexing | Elasticsearch / OpenSearch |
| Metrics & Observability | Spring Boot Actuator, Micrometer, Prometheus, Grafana |
| Scheduling | Spring Task Scheduling (`@Scheduled`) / Quartz Scheduler |
| Message Broker | Apache Kafka (notification events, cache eviction events) |
| Security & Auth | Spring Security, JWT (Bearer tokens), Role-Based Access Control (RBAC) |

---

## 2. Overall Description

### 2.1 High-Level Flow

```
+-----------------------------------------------------------------------------------+
|                            Admin Web Management Console                           |
+-----------------------------------------------------------------------------------+
                                         │
                                         ▼
                     [HTTPS / TLS 1.3 Bearer JWT Authorization]
                                         │
                                         ▼
+───────────────────────────────────────────────────────────────────────────────────+
|                  API Gateway & Global RBAC Security Filter                        |
|   (Validates JWT signature, expiration, and role == "ADMIN"; prevents IDOR)     |
+───────────────────────────────────────────────────────────────────────────────────+
                                         │
                                         ▼
+───────────────────────────────────────────────────────────────────────────────────+
|                               Admin Controllers                                   |
+───────────────────────────────────────────────────────────────────────────────────+
                                         │
                                         ▼
+───────────────────────────────────────────────────────────────────────────────────+
|                         Admin Business Service Layer                              |
|   - Concurrency Check (Optimistic Locking)                                        |
|   - Transaction Orchestration                                                     |
|   - Asynchronous Notification Dispatch                                            |
|   - Append-Only Audit Logging Filter                                              |
+────────────────────────┬─────────────────────────┬────────────────────────────────+
                         │                         │
            ┌────────────┴──────────┐              ▼
            ▼                       ▼       +───────────────────────────────────────+
+──────────────────────+ +────────────────+ |               Redis                   |
| Primary Database     | | Read Replica   | | - Active Session Revocation Blacklist |
| (PostgreSQL Writes)  | | (Metrics/Logs) | | - Ephemeral Ticket Review Locks       |
| - Audit Logs         | | - Analytics    | | - Job Detail Caches                   |
| - Entity Updates     | | - Long Reads   | +───────────────────────────────────────+
+──────────────────────+ +────────────────+                  │
            │                                                ▼
            ▼                               +───────────────────────────────────────+
+──────────────────────+                    |             Apache Kafka              |
|   Elasticsearch      |                    | - notifications.email                 |
| (Index Eviction Sync)|                    | - audit.events                        |
+──────────────────────+                    +───────────────────────────────────────+
```

### 2.2 Entity Lifecycles & State Machines

#### Company Verification Lifecycle (UC-ADM-01)

```
[REGISTERED]
     │
     └── submitDocuments() ──► [PENDING]
                                  │
                                  ├── approveVerification() ──► [VERIFIED] (Badge Granted)
                                  │
                                  └── rejectVerification()  ──► [REJECTED] (Requires Resubmission)
```

#### Violation Report Ticket Lifecycle (UC-ADM-02)

```
[OPEN] (Created by Candidate / Recruiter)
  │
  ├── assignReviewer() / lockTicket() ──► [IN_REVIEW]
                                              │
                                              ├── dismissReport() ──► [REJECTED] (No violation found)
                                              │
                                              └── sanction()      ──► [RESOLVED] (Action: WARNING / BAN / TAKEDOWN)
```

#### Account Status Lifecycle (UC-ADM-03)

```
[ACTIVE]
   │
   └── banAccount() ──► [BANNED] (Sessions revoked in Redis, login blocked)
                           │
                           └── unbanAccount() ──► [ACTIVE]
```

#### Job Posting Moderation Lifecycle (UC-ADM-04)

```
[DRAFT] ──► [PUBLISHED]
                 │
                 ├── takedownJob() ──► [SUSPENDED] (Purged from Search Index & Cache)
                 │                        │
                 │                        └── restoreJob() ──► [PUBLISHED]
                 │
                 └── closeJob()    ──► [CLOSED]
```

### 2.3 Response Envelope

All API responses follow the standard GuardianWork JSON envelope:

```json
{
  "status": 200,
  "message": "Human-readable response message",
  "data": { ... }
}
```

For paginated listing responses, `data` uses the standard `PageResponse` model:

```json
{
  "data": {
    "content": [ ... ],
    "pageNumber": 0,
    "pageSize": 20,
    "totalElements": 150,
    "totalPages": 8,
    "first": true,
    "last": false
  }
}
```

### 2.4 Public vs Protected Endpoints

* **No Public Endpoints:** Every route under `/api/admin/**` is strictly protected.
* **Authentication:** Requires HTTP header `Authorization: Bearer <jwt>`.
* **Authorization / RBAC:** The JWT claims must contain `role: "ADMIN"`. Requests from any other role (`CANDIDATE`, `RECRUITER`, `GUEST`) must be rejected with HTTP `403 FORBIDDEN`.
* **Privacy Boundary:** Admin roles are blocked at the application/ORM layer from accessing plain text sensitive data (e.g., password hashes, confidential direct applicant messages unless explicitly linked to an active violation ticket evidence payload).

---

## 3. Data Models

### 3.1 AuditLog (PostgreSQL — `audit_logs` table)

The `audit_logs` table is an **append-only** table. PostgreSQL table grants must explicitly deny `UPDATE` and `DELETE` privileges to all operational database user accounts.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Auto-incrementing identifier |
| `admin_id` | BIGINT | NOT NULL, FK → `users.id` | ID of the authenticated administrator |
| `action` | VARCHAR(100) | NOT NULL | Action constant (e.g. `COMPANY_VERIFY`, `ACCOUNT_BAN`) |
| `target_type` | VARCHAR(50) | NOT NULL | Target entity type (`COMPANY`, `USER`, `JOB`, `REPORT`, `CRON_JOB`) |
| `target_id` | VARCHAR(100) | NOT NULL | Identifier of the target entity |
| `old_payload` | JSONB | nullable | Snapshot of entity state prior to mutation |
| `new_payload` | JSONB | nullable | Snapshot of entity state after mutation |
| `reason` | TEXT | nullable | Admin-provided justification note |
| `ip_address` | VARCHAR(45) | NOT NULL | Client IPv4 / IPv6 address |
| `user_agent` | VARCHAR(255) | nullable | Client User-Agent header |
| `created_at` | TIMESTAMPTZ | NOT NULL, immutable | `DEFAULT now()` |

---

### 3.2 Company (PostgreSQL — `companies` table)

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Company identifier |
| `name` | VARCHAR(255) | NOT NULL | Legal or brand name |
| `tax_code` | VARCHAR(50) | NOT NULL, UNIQUE | Official government tax identifier |
| `registration_certificate_url` | TEXT | NOT NULL | Document storage URL (S3 / Cloud Storage) |
| `verification_status` | VARCHAR(30) | NOT NULL, DEFAULT `'PENDING'` | `PENDING`, `VERIFIED`, `REJECTED` |
| `rejection_reason` | TEXT | nullable | Populated if status is `REJECTED` |
| `verified_by` | BIGINT | nullable, FK → `users.id` | Admin user ID who reviewed |
| `verified_at` | TIMESTAMPTZ | nullable | Timestamp of verification |
| `is_banned` | BOOLEAN | NOT NULL, DEFAULT false | Flag indicating account ban status |
| `version` | BIGINT | NOT NULL, DEFAULT 0 | Optimistic concurrency control counter |
| `created_at` | TIMESTAMPTZ | NOT NULL | `DEFAULT now()` |
| `updated_at` | TIMESTAMPTZ | NOT NULL | `DEFAULT now()` |

---

### 3.3 ViolationReport (PostgreSQL — `violation_reports` table)

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Report identifier |
| `reporter_id` | BIGINT | NOT NULL, FK → `users.id` | Filing user |
| `target_type` | VARCHAR(30) | NOT NULL | `COMPANY`, `CANDIDATE`, `JOB` |
| `target_id` | VARCHAR(100) | NOT NULL | Target entity ID |
| `reason_category` | VARCHAR(50) | NOT NULL | E.g., `FRAUD`, `SPAM`, `HARASSMENT`, `FALSE_JOB` |
| `description` | TEXT | NOT NULL | Detailed narrative from reporter |
| `evidence_urls` | JSONB | nullable | Array of uploaded proof URLs |
| `status` | VARCHAR(30) | NOT NULL, DEFAULT `'OPEN'` | `OPEN`, `IN_REVIEW`, `RESOLVED`, `REJECTED` |
| `assigned_admin_id` | BIGINT | nullable, FK → `users.id` | Admin who locked/is reviewing ticket |
| `resolution_action` | VARCHAR(50) | nullable | `WARNING`, `BAN_ACCOUNT`, `TAKEDOWN_JOB`, `NO_ACTION` |
| `resolution_notes` | TEXT | nullable | Internal admin review notes |
| `version` | BIGINT | NOT NULL, DEFAULT 0 | Optimistic locking version for concurrency |
| `resolved_at` | TIMESTAMPTZ | nullable | Resolution timestamp |
| `created_at` | TIMESTAMPTZ | NOT NULL | `DEFAULT now()` |
| `updated_at` | TIMESTAMPTZ | NOT NULL | `DEFAULT now()` |

---

### 3.4 UserAccountBan (PostgreSQL — `user_account_bans` table)

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Ban record ID |
| `user_id` | BIGINT | NOT NULL, FK → `users.id` | Target user account |
| `banned_by` | BIGINT | NOT NULL, FK → `users.id` | Admin who executed the ban |
| `reason` | TEXT | NOT NULL | Violation reason |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT true | `true` = currently enforced |
| `banned_at` | TIMESTAMPTZ | NOT NULL | `DEFAULT now()` |
| `expires_at` | TIMESTAMPTZ | nullable | `null` = permanent ban; timestamp = temporary |
| `unbanned_at` | TIMESTAMPTZ | nullable | Populated upon unban action |
| `unbanned_by` | BIGINT | nullable, FK → `users.id` | Admin who unbanned |
| `unban_reason` | TEXT | nullable | Justification for restoration |

---

### 3.5 JobModeration (PostgreSQL — `jobs` table moderation extension)

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Job posting ID |
| `company_id` | BIGINT | NOT NULL, FK → `companies.id` | Hiring company |
| `title` | VARCHAR(255) | NOT NULL | Job title |
| `status` | VARCHAR(30) | NOT NULL, DEFAULT `'DRAFT'` | `DRAFT`, `PUBLISHED`, `SUSPENDED`, `CLOSED` |
| `takedown_reason` | TEXT | nullable | Stored when suspended by admin |
| `suspended_by` | BIGINT | nullable, FK → `users.id` | Admin ID who initiated takedown |
| `suspended_at` | TIMESTAMPTZ | nullable | Timestamp of suspension |
| `version` | BIGINT | NOT NULL, DEFAULT 0 | Optimistic locking counter |
| `created_at` | TIMESTAMPTZ | NOT NULL | `DEFAULT now()` |
| `updated_at` | TIMESTAMPTZ | NOT NULL | `DEFAULT now()` |

---

### 3.6 CronJobConfig & CronJobExecution (PostgreSQL)

#### `cron_job_configs` Table

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Job config ID |
| `job_key` | VARCHAR(100) | NOT NULL, UNIQUE | Canonical identifier (e.g. `EXPIRED_POST_SWEEPER`) |
| `display_name` | VARCHAR(255) | NOT NULL | Human-readable title |
| `cron_expression` | VARCHAR(50) | NOT NULL | Quartz / Spring cron expression |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT `'ACTIVE'` | `ACTIVE`, `PAUSED` |
| `description` | TEXT | nullable | Purpose of background worker |
| `last_run_at` | TIMESTAMPTZ | nullable | Timestamp of latest run |
| `last_status` | VARCHAR(30) | nullable | `SUCCESS`, `FAILED`, `RUNNING` |
| `updated_at` | TIMESTAMPTZ | NOT NULL | `DEFAULT now()` |

#### `cron_job_executions` Table

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Execution run ID |
| `job_key` | VARCHAR(100) | NOT NULL | Reference to `cron_job_configs.job_key` |
| `triggered_by` | VARCHAR(50) | NOT NULL | `SCHEDULED` or `MANUAL:adminId` |
| `start_time` | TIMESTAMPTZ | NOT NULL | Start timestamp |
| `end_time` | TIMESTAMPTZ | nullable | Completion timestamp |
| `execution_status` | VARCHAR(30) | NOT NULL | `RUNNING`, `SUCCESS`, `FAILED` |
| `log_output` | TEXT | nullable | Captured stack trace or execution summary |

---

### 3.7 Ephemeral Storage (Redis Key Formats)

| Key Pattern | Data Type | TTL | Purpose |
|---|---|---|---|
| `auth:revoked_tokens:{userId}` | Set of String | 86,400 s (24 h) | Contains revoked JWT jti / user IDs for immediate ban enforcement |
| `admin:review_lock:report:{reportId}` | String (`adminId`) | 900 s (15 min) | Transient soft lock preventing two admins from reviewing the same ticket simultaneously |
| `cache:job:{jobId}` | String (JSON) | 3,600 s | Cached job payload; purged on takedown |
| `metrics:system:snapshot` | String (JSON) | 60 s | Cached pre-aggregated health metrics |

---

### 3.8 Database Indexes

| Index | Table | Column(s) | Purpose |
|---|---|---|---|
| `idx_audit_logs_admin_id` | `audit_logs` | `admin_id` | Filter audit logs by administrator |
| `idx_audit_logs_target` | `audit_logs` | `target_type`, `target_id` | Query audit trail by entity |
| `idx_audit_logs_created_at` | `audit_logs` | `created_at DESC` | Reverse-chronological audit timeline |
| `idx_companies_verification_status` | `companies` | `verification_status` | Filter pending verifications |
| `idx_violation_reports_status` | `violation_reports` | `status` | Fetch unresolved tickets |
| `idx_violation_reports_target` | `violation_reports` | `target_type`, `target_id` | Correlate repeated violations |
| `idx_user_account_bans_user_active` | `user_account_bans` | `user_id`, `is_active` | Rapid active ban verification |
| `idx_jobs_status` | `jobs` | `status` | Filter published vs suspended jobs |
| `idx_cron_executions_job_time` | `cron_job_executions` | `job_key`, `start_time DESC` | Query job history logs |

---

## 4. Functional Requirements

### 4.1 Company Profile Verification (UC-ADM-01)

| ID | Requirement |
|---|---|
| **ADM-VER-01** | The system **shall** allow an Admin to view a paginated list of company accounts filtered by verification status (`PENDING`, `VERIFIED`, `REJECTED`). |
| **ADM-VER-02** | The system **shall** provide full read access for the Admin to the uploaded Business Registration Certificate URL, tax code, and submitted corporate profile details. |
| **ADM-VER-03** | When approving a company, the system **shall** update `verification_status` to `VERIFIED`, record `verified_by` and `verified_at`, and set the `is_verified` flag. |
| **ADM-VER-04** | When rejecting a company, the system **shall** require a non-empty `rejectionReason`, set `verification_status` to `REJECTED`, and retain the reason in the database. |
| **ADM-VER-05** | Upon status transition (`VERIFIED` or `REJECTED`), the system **shall** publish a notification event to Kafka to dispatch an email to the company's registered address. |
| **ADM-VER-06** | The system **shall** record an immutable entry in `audit_logs` capturing the decision, old status, new status, and admin ID. |

---

### 4.2 Violation Report Resolution (UC-ADM-02)

| ID | Requirement |
|---|---|
| **ADM-REP-01** | The system **shall** provide a filterable queue of violation reports filtered by status (`OPEN`, `IN_REVIEW`, `RESOLVED`, `REJECTED`) and target type (`COMPANY`, `CANDIDATE`, `JOB`). |
| **ADM-REP-02** | When an Admin begins reviewing a ticket, the system **shall** acquire an ephemeral Redis review lock (`admin:review_lock:report:{reportId}`) for 15 minutes to warn other admins. |
| **ADM-REP-03** | The system **shall** verify the entity `version` column upon resolution submission; if `version` has been changed by another admin, the transaction **must** fail with HTTP `409 Conflict`. |
| **ADM-REP-04** | When resolving a report, the system **shall** allow the Admin to select a resolution action: `WARNING`, `BAN_ACCOUNT`, `TAKEDOWN_JOB`, or `NO_ACTION` alongside mandatory `resolutionNotes`. |
| **ADM-REP-05** | If `BAN_ACCOUNT` is selected, the system **shall** atomically invoke the account ban procedure (UC-ADM-03). |
| **ADM-REP-06** | If `TAKEDOWN_JOB` is selected, the system **shall** atomically invoke the job takedown procedure (UC-ADM-04). |
| **ADM-REP-07** | The system **shall** update report status to `RESOLVED` (or `REJECTED` if dismissed) and release the Redis review lock. |

---

### 4.3 Account Ban & Unban Management (UC-ADM-03)

| ID | Requirement |
|---|---|
| **ADM-BAN-01** | The system **shall** permit an Admin to ban any user or company account by providing `userId`, `reason`, and an optional `expiresAt` timestamp (null denotes permanent ban). |
| **ADM-BAN-02** | When an account is banned, the system **shall** set `users.status = 'BANNED'` (or `companies.is_banned = true`) and persist a row in `user_account_bans`. |
| **ADM-BAN-03** | Immediately following a ban, the system **shall** add the banned user's ID to the Redis revocation set `auth:revoked_tokens:{userId}` to invalidate all existing JWT bearer tokens. |
| **ADM-BAN-04** | Subsequent API requests from revoked tokens **shall** be blocked by the authentication filter with HTTP `401 UNAUTHENTICATED` (`40101 ACCOUNT_BANNED`). |
| **ADM-BAN-05** | The system **shall** allow an Admin to unban a previously banned account by supplying an `unbanReason`. |
| **ADM-BAN-06** | Upon unban, the system **shall** update `user_account_bans.is_active = false`, set `users.status = 'ACTIVE'`, and remove the user ID from the Redis revocation set. |
| **ADM-BAN-07** | Every ban and unban operation **shall** be written to `audit_logs` with before-and-after snapshots. |

---

### 4.4 Job Posting Moderation & Takedown (UC-ADM-04)

| ID | Requirement |
|---|---|
| **ADM-JOB-01** | The system **shall** allow an Admin to force-suspend (`TAKEDOWN`) any job posting currently in `PUBLISHED` status by providing a mandatory `takedownReason`. |
| **ADM-JOB-02** | Upon takedown, the system **shall** set `jobs.status = 'SUSPENDED'`, record `suspended_by` and `suspended_at`, and increment `version`. |
| **ADM-JOB-03** | The system **shall** immediately evict the job from Redis cache key `cache:job:{jobId}`. |
| **ADM-JOB-04** | The system **shall** publish an event `job.eviction` to Kafka to trigger an immediate removal from Elasticsearch search indices. |
| **ADM-JOB-05** | Candidates attempting to view a suspended job **shall** receive HTTP `404 NOT_FOUND` or `40061 JOB_SUSPENDED`. |
| **ADM-JOB-06** | The system **shall** record the action, old state, new state, and reason in `audit_logs`. |

---

### 4.5 System Health & Traffic Monitoring (UC-ADM-05)

| ID | Requirement |
|---|---|
| **ADM-MON-01** | The system **shall** expose an administrative endpoint to retrieve live infrastructure telemetry: CPU utilization, JVM heap memory usage, active threads, and HikariCP connection pool status (active, idle, max connections). |
| **ADM-MON-02** | The system **shall** expose application-level traffic metrics: Concurrent Active Users (CCU in last 5 minutes via active tokens), request rate (RPM), error rate (5xx errors / min). |
| **ADM-MON-03** | All read queries for aggregated monitoring telemetry **must** be routed strictly to PostgreSQL **Read Replicas** or pre-aggregated Redis cache buffers to prevent resource contention on the primary transactional database. |
| **ADM-MON-04** | Telemetry metrics **shall not** execute full-table scans against transactional tables during real-time dashboard polls. |

---

### 4.6 Background Cron Job Management (UC-ADM-06)

| ID | Requirement |
|---|---|
| **ADM-CRON-01** | The system **shall** provide an API to list all registered background workers, showing `jobKey`, `displayName`, `cronExpression`, `status` (`ACTIVE`/`PAUSED`), `lastRunAt`, and `lastStatus`. |
| **ADM-CRON-02** | The system **shall** permit an Admin to manually trigger an immediate out-of-schedule execution of a designated cron job. |
| **ADM-CRON-03** | When triggered manually, the worker **shall** execute asynchronously, logging the triggering admin's ID (`MANUAL:adminId`) in `cron_job_executions`. |
| **ADM-CRON-04** | The system **shall** permit an Admin to adjust the cron expression frequency or toggle the status between `ACTIVE` and `PAUSED`. |
| **ADM-CRON-05** | The system **shall** provide an API endpoint to retrieve the execution history and error logs for any specified cron job. |

---

### 4.7 Audit Trail & Governance (Architectural Risk Mitigation)

| ID | Requirement |
|---|---|
| **ADM-AUD-01** | Every state-mutating request (`POST`, `PUT`, `DELETE`, `PATCH`) handled by any Admin service **must** record an entry in `audit_logs`. |
| **ADM-AUD-02** | Audit log entries **shall** be persisted within the same database transaction as the entity mutation, or via a reliable transactional outbox. |
| **ADM-AUD-03** | The system **shall** prevent any Admin user from updating, modifying, or deleting records from the `audit_logs` table. |
| **ADM-AUD-04** | The system **shall** expose a read-only endpoint for Admins to search and filter audit logs by `adminId`, `action`, `targetType`, and date range. |
| **ADM-AUD-05** | Admin APIs **must not** return unmasked raw passwords or private direct applicant messages unless associated with verified ticket evidence. |

---

## 5. Flow Diagrams

### 5.1 Company Verification Flow (UC-ADM-01)

```
Admin Web App                  AdminController             AdminCompanyService         Database (PostgreSQL)          Kafka
      │                               │                             │                            │                      │
      │── PUT /api/admin/companies/──►│                             │                            │                      │
      │   {id}/verify                 │                             │                            │                      │
      │   { status, reason }          │── verifyCompany(id, dto) ──►│                            │                      │
      │                               │                             │── findById(id) ───────────►│                      │
      │                               │                             │◄── Company (PENDING) ──────│                      │
      │                               │                             │                            │                      │
      │                               │                             │── [status == VERIFIED]?    │                      │
      │                               │                             │   ├── update status        │                      │
      │                               │                             │   ├── record verifiedBy/At │                      │
      │                               │                             │── [status == REJECTED]?    │                      │
      │                               │                             │   └── set rejectionReason  │                      │
      │                               │                             │                            │                      │
      │                               │                             │── save(company) ──────────►│                      │
      │                               │                             │── insert(auditLog) ───────►│                      │
      │                               │                             │                            │                      │
      │                               │                             │── publishNotification() ─────────────────────────►│
      │                               │                             │                            │  (email.notification)│
      │                               │◄── CompanyResponse ─────────│                            │                      │
      │◄── 200 OK { CompanyResponse }─│                             │                            │                      │
```

---

### 5.2 Violation Report Resolution Flow with Optimistic Concurrency (UC-ADM-02)

```
Admin A (Alice)          Admin B (Bob)            AdminReportService              PostgreSQL (version=1)         Redis
      │                        │                          │                                  │                     │
      │── GET /reports/{id} ───┼─────────────────────────►│── setReviewLock(reportId) ───────┼────────────────────►│
      │◄── Report (version=1) ─┼──────────────────────────│◄── return report (v=1) ──────────│                     │
      │                        │                          │                                  │                     │
      │                        │── GET /reports/{id} ────►│── checkReviewLock(reportId) ─────┼────────────────────►│
      │                        │                          │   (Returns warning: in review)   │                     │
      │                        │◄── Report (version=1) ───│                                  │                     │
      │                        │                          │                                  │                     │
      │── PUT /resolve ────────┼─────────────────────────►│                                  │                     │
      │   { action: "BAN",     │                          │── UPDATE reports SET             │                     │
      │     expectedVersion:1 }│                          │   status='RESOLVED',             │                     │
      │                        │                          │   version=2 WHERE id=X           │                     │
      │                        │                          │   AND version=1                  │                     │
      │                        │                          │── save() ───────────────────────►│ [rows updated = 1]  │
      │                        │                          │── releaseReviewLock() ───────────┼────────────────────►│
      │◄── 200 OK (Resolved) ──┼──────────────────────────│                                  │                     │
      │                        │                          │                                  │                     │
      │                        │── PUT /resolve ─────────►│                                  │                     │
      │                        │   { action: "REJECT",    │── UPDATE reports SET             │                     │
      │                        │     expectedVersion:1 }  │   status='REJECTED',             │                     │
      │                        │                          │   version=2 WHERE id=X           │                     │
      │                        │                          │   AND version=1                  │                     │
      │                        │                          │── save() ───────────────────────►│ [rows updated = 0]  │
      │                        │                          │   -> throws OptimisticLockEx     │                     │
      │                        │◄── 409 Conflict ─────────│                                  │                     │
```

---

### 5.3 Account Ban & Distributed Token Invalidation (UC-ADM-03)

```
Admin Web App              AdminAccountService               PostgreSQL               Redis (Revocation Set)
      │                             │                            │                              │
      │── POST /accounts/{id}/ban ─►│                            │                              │
      │   { reason, expiresAt }     │── checkUserExists(id) ────►│                              │
      │                             │◄── User (ACTIVE) ──────────│                              │
      │                             │                            │                              │
      │                             │── BEGIN TRANSACTION        │                              │
      │                             │── update user status ─────►│                              │
      │                             │── insert user_account_bans►│                              │
      │                             │── insert audit_logs ──────►│                              │
      │                             │── COMMIT                   │                              │
      │                             │                            │                              │
      │                             │── SADD auth:revoked_tokens:{userId} ─────────────────────►│
      │                             │   (Immediate token kill switch)                           │
      │◄── 200 OK (Account Banned) ─│                                                           │
      │                             │                                                           │
Candidate Client App         AuthFilter / Gateway                                              │
      │                             │                                                           │
      │── GET /api/jobs (JWT) ─────►│                                                           │
      │                             │── SISMEMBER auth:revoked_tokens:{userId} ────────────────►│
      │                             │◄── returns true (Revoked) ────────────────────────────────│
      │◄── 401 UNAUTHENTICATED ─────│                                                           │
          (ACCOUNT_BANNED)          │                                                           │
```

---

### 5.4 Job Takedown & Multi-Store Eviction Flow (UC-ADM-04)

```
Admin Web App              AdminJobService                   PostgreSQL           Redis Cache      Kafka / Elasticsearch
      │                           │                              │                     │                     │
      │── PUT /jobs/{id}/takedown►│                              │                     │                     │
      │   { reason: "Fraud" }     │── findById(id) ─────────────►│                     │                     │
      │                           │◄── Job (PUBLISHED) ──────────│                     │                     │
      │                           │                              │                     │                     │
      │                           │── UPDATE jobs SET            │                     │                     │
      │                           │   status = 'SUSPENDED' ─────►│                     │                     │
      │                           │── insert audit_logs ────────►│                     │                     │
      │                           │                              │                     │                     │
      │                           │── DEL cache:job:{id} ─────────────────────────────►│                     │
      │                           │                              │                     │                     │
      │                           │── publishEvent("job.evict", {jobId}) ───────────────────────────────────►│
      │                           │                              │                     │                     │── [Sync Eviction]
      │                           │                              │                     │                     │   Removes from ES
      │◄── 200 OK (Suspended) ────│                              │                     │                     │
```

---

## 6. Business Rules

### Role & Access Governance

| ID | Rule |
|---|---|
| **BR-ADM-01** | All endpoints prefixed with `/api/admin/` require the caller's JWT token to contain the explicit claim `role: "ADMIN"`. Requests with any other role or missing token are rejected with `403 FORBIDDEN` or `401 UNAUTHENTICATED`. |
| **BR-ADM-02** | Administrators **must not** have read access to raw password hashes or unencrypted candidate-recruiter messaging channels, unless such communication was uploaded as an immutable piece of evidence in an active `violation_reports` record. |
| **BR-ADM-03** | Self-action restriction: An administrator cannot ban their own account or modify their own role permissions. |

### Company Verification Rules

| ID | Rule |
|---|---|
| **BR-ADM-04** | A company profile can only be approved (`VERIFIED`) or rejected (`REJECTED`) if its current status is `PENDING`. |
| **BR-ADM-05** | If an admin rejects a verification request, a non-blank `rejectionReason` (at least 10 characters) is mandatory. |
| **BR-ADM-06** | Once marked as `VERIFIED`, a company account receives verified search ranking privileges and access to direct outreach features. |

### Violation Report & Moderation Rules

| ID | Rule |
|---|---|
| **BR-ADM-07** | A report ticket in `RESOLVED` or `REJECTED` state is terminal and cannot be reopened via standard review endpoints. |
| **BR-ADM-08** | Concurrency resolution: Any status transition on `violation_reports` requires verifying that the database `version` matches `expectedVersion`. On mismatch, the API aborts and returns `409 CONFLICT`. |
| **BR-ADM-09** | When taking punitive action (`BAN_ACCOUNT`), the action must be executed atomically alongside the report resolution in a single transactional unit. |

### Account Suspension Rules

| ID | Rule |
|---|---|
| **BR-ADM-10** | When an account is banned, all associated active JWT sessions are immediately invalidated by publishing the user ID to the Redis distributed revocation blacklist. |
| **BR-ADM-11** | If a company account is banned, all active job postings belonging to that company must be automatically transitioned to `SUSPENDED` status and evicted from the search index. |
| **BR-ADM-12** | Unbanning an account requires an active ban record. The previous ban history remains immutable in `user_account_bans`. |

### Job Moderation Rules

| ID | Rule |
|---|---|
| **BR-ADM-13** | Only jobs in `PUBLISHED` status can be taken down (`SUSPENDED`). Already closed or archived jobs cannot be suspended. |
| **BR-ADM-14** | Takedown must atomically trigger cache invalidation in Redis (`cache:job:{id}`) and asynchronous document deletion in Elasticsearch. |

### Audit & System Governance Rules

| ID | Rule |
|---|---|
| **BR-ADM-15** | Every state-altering HTTP request (`POST`, `PUT`, `PATCH`, `DELETE`) executed by an Admin must generate an immutable audit log record. |
| **BR-ADM-16** | The `audit_logs` table is strictly append-only; database credentials used by the application service must not have `UPDATE` or `DELETE` grants on this table. |
| **BR-ADM-17** | System monitoring and telemetry queries must read from read replicas or Redis pre-aggregated buffers to prevent disruption of transactional OLTP traffic. |

---

## 7. Error Catalogue

### Administrative Authentication & Authorization Errors

| Error Code | HTTP | Constant | Trigger |
|---|---|---|---|
| `40100` | 401 | `UNAUTHENTICATED` | Missing or invalid Bearer JWT token |
| `40101` | 401 | `ACCOUNT_BANNED` | Caller account has been banned / revoked in Redis blacklist |
| `40300` | 403 | `FORBIDDEN` | Caller lacks `ADMIN` role claim in JWT |
| `40301` | 403 | `SELF_MODIFICATION_PROHIBITED` | Admin attempted to ban or modify their own administrative account |

### Entity Verification & Moderation Errors

| Error Code | HTTP | Constant | Trigger |
|---|---|---|---|
| `40000` | 400 | `INVALID_INPUT` | Request validation failure (missing reason, invalid syntax) |
| `40010` | 400 | `COMPANY_NOT_PENDING` | Attempted to verify/reject a company whose status is not `PENDING` |
| `40011` | 404 | `COMPANY_NOT_FOUND` | Company ID does not exist |
| `40020` | 400 | `REPORT_ALREADY_RESOLVED` | Attempted to mutate a report already in `RESOLVED` or `REJECTED` status |
| `40021` | 404 | `REPORT_NOT_FOUND` | Violation report ID does not exist |
| `40022` | 409 | `CONCURRENT_MODIFICATION` | Report ticket version mismatch (Optimistic Locking failure) |
| `40030` | 400 | `ACCOUNT_ALREADY_BANNED` | Attempting to ban an already banned user |
| `40031` | 400 | `ACCOUNT_NOT_BANNED` | Attempting to unban an active user |
| `40032` | 404 | `USER_NOT_FOUND` | Target user account does not exist |
| `40040` | 400 | `JOB_NOT_PUBLISHED` | Cannot takedown a job that is not currently `PUBLISHED` |
| `40041` | 404 | `JOB_NOT_FOUND` | Job posting ID does not exist |
| `40050` | 404 | `CRON_JOB_NOT_FOUND` | Cron job key not registered in system |
| `40051` | 400 | `INVALID_CRON_EXPRESSION` | Submitted cron string fails Quartz / Spring cron parser |
| `50000` | 500 | `INTERNAL_SERVER_ERROR` | Unexpected backend or database failure |

---

## 8. API Specification

### Conventions

- **Base URL:** `http://localhost:8080`
- **Auth:** `Authorization: Bearer <jwt>` (Required for all endpoints)
- **Required Role:** `ADMIN`
- **Content-Type:** `application/json`
- **ID Type:** Numeric `Long` / `Bigserial` (e.g. `1024`)

---

### 8.1 Company Profile Verification Endpoints

#### `GET /api/admin/companies/verifications` — List Verification Queue

Retrieves a paginated list of companies filtered by status.

**Query Parameters**

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `status` | String | No | `PENDING` | Filter by `PENDING`, `VERIFIED`, `REJECTED`, or `ALL` |
| `page` | Integer | No | `0` | Zero-based page index |
| `size` | Integer | No | `20` | Items per page (max `50`) |
| `sort` | String | No | `createdAt,asc` | Sort criteria |

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Company verification list retrieved successfully",
  "data": {
    "content": [
      {
        "id": 105,
        "name": "Acme Cyber Security Corp",
        "taxCode": "0312345678",
        "registrationCertificateUrl": "https://storage.guardianwork.com/certs/acme-cert.pdf",
        "verificationStatus": "PENDING",
        "createdAt": "2026-09-20T10:15:30Z",
        "version": 0
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

#### `PUT /api/admin/companies/{id}/verify` — Verify or Reject Company

Issues an approval badge or rejects the company's business registration document.

**Path Parameters**

| Parameter | Type | Description |
|---|---|---|
| `id` | Long | Unique identifier of the company |

**Request Body**

```json
{
  "status": "VERIFIED",
  "rejectionReason": null
}
```

**Validation**

| Field | Rule |
|---|---|
| `status` | Must be `VERIFIED` or `REJECTED` |
| `rejectionReason` | Mandatory if status is `REJECTED` (min 10 chars, max 1000 chars); must be null if `VERIFIED` |

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Company profile verification decision recorded",
  "data": {
    "id": 105,
    "name": "Acme Cyber Security Corp",
    "verificationStatus": "VERIFIED",
    "verifiedBy": 1,
    "verifiedAt": "2026-09-21T09:30:00Z",
    "rejectionReason": null
  }
}
```

**Error Responses**

| Condition | Error Code | HTTP |
|---|---|---|
| Company not found | `40011` | 404 |
| Status is not PENDING | `40010` | 400 |
| Rejection without reason | `40000` | 400 |

---

### 8.2 Violation Report Resolution Endpoints

#### `GET /api/admin/reports` — List Violation Reports

Retrieves a paginated list of submitted violation tickets.

**Query Parameters**

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `status` | String | No | `OPEN` | `OPEN`, `IN_REVIEW`, `RESOLVED`, `REJECTED` |
| `targetType` | String | No | null | `COMPANY`, `CANDIDATE`, `JOB` |
| `page` | Integer | No | `0` | Page index |
| `size` | Integer | No | `20` | Page size |

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Violation reports retrieved successfully",
  "data": {
    "content": [
      {
        "id": 501,
        "reporterId": 42,
        "targetType": "COMPANY",
        "targetId": "105",
        "reasonCategory": "FRAUD",
        "description": "Recruiter asked for recruitment upfront deposit fee.",
        "evidenceUrls": ["https://storage.guardianwork.com/evidence/chat-screenshot.png"],
        "status": "OPEN",
        "assignedAdminId": null,
        "version": 0,
        "createdAt": "2026-09-21T08:00:00Z"
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

#### `PUT /api/admin/reports/{id}/resolve` — Resolve Violation Report

Finalizes enforcement action on a report with optimistic concurrency protection.

**Path Parameters**

| Parameter | Type | Description |
|---|---|---|
| `id` | Long | Violation Report ticket ID |

**Request Body**

```json
{
  "action": "BAN_ACCOUNT",
  "resolutionNotes": "Verified fraudulent upfront deposit request via uploaded bank details evidence.",
  "expectedVersion": 0
}
```

**Validation**

| Field | Rule |
|---|---|
| `action` | Mandatory; `WARNING`, `BAN_ACCOUNT`, `TAKEDOWN_JOB`, `NO_ACTION` |
| `resolutionNotes` | Mandatory (min 10 chars, max 2000 chars) |
| `expectedVersion` | Mandatory numeric version identifier |

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Violation report resolved successfully",
  "data": {
    "id": 501,
    "status": "RESOLVED",
    "resolutionAction": "BAN_ACCOUNT",
    "resolutionNotes": "Verified fraudulent upfront deposit request via uploaded bank details evidence.",
    "resolvedBy": 1,
    "resolvedAt": "2026-09-21T10:15:00Z",
    "version": 1
  }
}
```

**Error Responses**

| Condition | Error Code | HTTP |
|---|---|---|
| Report not found | `40021` | 404 |
| Already resolved/closed | `40020` | 400 |
| Concurrent update conflict | `40022` | 409 |

---

### 8.3 Account Ban & Unban Endpoints

#### `POST /api/admin/accounts/{userId}/ban` — Ban Account

Suspends account privileges and revokes distributed authentication tokens.

**Path Parameters**

| Parameter | Type | Description |
|---|---|---|
| `userId` | Long | Account ID to ban |

**Request Body**

```json
{
  "reason": "Violating community standards regarding fraudulent job postings.",
  "expiresAt": null
}
```

**Validation**

| Field | Rule |
|---|---|
| `reason` | Mandatory (min 5 chars, max 1000 chars) |
| `expiresAt` | Nullable; if provided, must be a future ISO-8601 timestamp |

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Account has been banned and active sessions revoked",
  "data": {
    "userId": 42,
    "status": "BANNED",
    "bannedBy": 1,
    "bannedAt": "2026-09-21T10:20:00Z",
    "expiresAt": null,
    "reason": "Violating community standards regarding fraudulent job postings."
  }
}
```

**Error Responses**

| Condition | Error Code | HTTP |
|---|---|---|
| User not found | `40032` | 404 |
| User already banned | `40030` | 400 |
| Admin banning self | `40301` | 403 |

---

#### `POST /api/admin/accounts/{userId}/unban` — Unban Account

Restores platform access and removes the user from the revocation blacklist.

**Path Parameters**

| Parameter | Type | Description |
|---|---|---|
| `userId` | Long | Account ID to unban |

**Request Body**

```json
{
  "unbanReason": "Penalty duration expired; appeal accepted by administration."
}
```

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Account has been unbanned successfully",
  "data": {
    "userId": 42,
    "status": "ACTIVE",
    "unbannedBy": 1,
    "unbannedAt": "2026-09-21T10:30:00Z"
  }
}
```

---

### 8.4 Job Moderation Endpoints

#### `PUT /api/admin/jobs/{id}/takedown` — Takedown Job Posting

Suspends a published job and purges it from cache and search engines.

**Path Parameters**

| Parameter | Type | Description |
|---|---|---|
| `id` | Long | Job posting ID |

**Request Body**

```json
{
  "takedownReason": "Fraudulent salary claims and deceptive company representation."
}
```

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Job posting has been taken down and removed from public search",
  "data": {
    "jobId": 801,
    "status": "SUSPENDED",
    "suspendedBy": 1,
    "suspendedAt": "2026-09-21T10:35:00Z",
    "takedownReason": "Fraudulent salary claims and deceptive company representation."
  }
}
```

**Error Responses**

| Condition | Error Code | HTTP |
|---|---|---|
| Job not found | `40041` | 404 |
| Job not in PUBLISHED status | `40040` | 400 |

---

### 8.5 System Health & Metrics Endpoints

#### `GET /api/admin/system/health` — Infrastructure Health Snapshot

Provides instant infrastructure telemetry without impacting primary transactional tables.

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "System health snapshot retrieved",
  "data": {
    "systemTime": "2026-09-21T10:40:00Z",
    "uptimeSeconds": 864200,
    "jvm": {
      "heapUsedBytes": 1073741824,
      "heapMaxBytes": 4294967296,
      "activeThreads": 84
    },
    "database": {
      "primaryConnectionPool": {
        "activeConnections": 12,
        "idleConnections": 18,
        "maxConnections": 30
      },
      "readReplicaConnectionPool": {
        "activeConnections": 4,
        "idleConnections": 26,
        "maxConnections": 30
      }
    },
    "redis": {
      "status": "UP",
      "usedMemoryRssBytes": 268435456
    }
  }
}
```

---

#### `GET /api/admin/system/metrics` — Traffic & Performance Analytics

Returns real-time traffic volume and system error rates.

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "System traffic metrics retrieved",
  "data": {
    "concurrentActiveUsers": 1420,
    "requestsPerMinute": 3850,
    "errorRatePercentage": 0.04,
    "sampledAt": "2026-09-21T10:40:00Z"
  }
}
```

---

### 8.6 Background Cron Job Endpoints

#### `GET /api/admin/cron-jobs` — List Cron Jobs

Lists all registered background tasks and execution states.

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Cron jobs retrieved successfully",
  "data": [
    {
      "jobKey": "EXPIRED_POST_SWEEPER",
      "displayName": "Expired Job Postings Sweeper",
      "cronExpression": "0 0 1 * * ?",
      "status": "ACTIVE",
      "lastRunAt": "2026-09-21T01:00:00Z",
      "lastStatus": "SUCCESS"
    },
    {
      "jobKey": "ANALYTICS_ETL_PIPELINE",
      "displayName": "Data Warehouse Nightly Aggregation",
      "cronExpression": "0 30 2 * * ?",
      "status": "ACTIVE",
      "lastRunAt": "2026-09-21T02:30:00Z",
      "lastStatus": "SUCCESS"
    }
  ]
}
```

---

#### `POST /api/admin/cron-jobs/{jobKey}/trigger` — Manually Trigger Cron Job

Executes a background task immediately in an asynchronous thread.

**Path Parameters**

| Parameter | Type | Description |
|---|---|---|
| `jobKey` | String | Unique identifier of the cron job |

**Response `202 Accepted`**

```json
{
  "status": 202,
  "message": "Background job triggered successfully in asynchronous mode",
  "data": {
    "jobKey": "EXPIRED_POST_SWEEPER",
    "executionId": 9042,
    "triggeredBy": "MANUAL:1",
    "startTime": "2026-09-21T10:45:00Z",
    "status": "RUNNING"
  }
}
```

---

#### `PATCH /api/admin/cron-jobs/{jobKey}/config` — Update Cron Configuration

Modifies the schedule frequency or pauses/resumes the background worker.

**Request Body**

```json
{
  "cronExpression": "0 0 3 * * ?",
  "status": "PAUSED"
}
```

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Cron job configuration updated successfully",
  "data": {
    "jobKey": "EXPIRED_POST_SWEEPER",
    "cronExpression": "0 0 3 * * ?",
    "status": "PAUSED",
    "updatedAt": "2026-09-21T10:47:00Z"
  }
}
```

---

### 8.7 Audit Trail Endpoints

#### `GET /api/admin/audit-logs` — Query Audit Trail

Retrieves an immutable record of historical administrative actions.

**Query Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `adminId` | Long | No | Filter by specific admin ID |
| `action` | String | No | E.g. `COMPANY_VERIFY`, `ACCOUNT_BAN`, `JOB_TAKEDOWN` |
| `targetType` | String | No | `COMPANY`, `USER`, `JOB`, `REPORT` |
| `startDate` | ISO-8601 | No | Beginning of time window |
| `endDate` | ISO-8601 | No | End of time window |
| `page` | Integer | No | Page number (default `0`) |
| `size` | Integer | No | Page size (default `20`) |

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Audit logs retrieved successfully",
  "data": {
    "content": [
      {
        "id": 12044,
        "adminId": 1,
        "action": "ACCOUNT_BAN",
        "targetType": "USER",
        "targetId": "42",
        "oldPayload": { "status": "ACTIVE" },
        "newPayload": { "status": "BANNED", "expiresAt": null },
        "reason": "Violating community standards regarding fraudulent job postings.",
        "ipAddress": "192.168.1.100",
        "createdAt": "2026-09-21T10:20:00Z"
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

## 9. Infrastructure Architecture

### 9.1 Redis — Blacklist & Ephemeral State Management

```
Implementation:     AdminRedisService
Spring Client:      StringRedisTemplate
Key Expire Policy:  Explicit volatile TTLs
```

* **Distributed Session Revocation:** When an account is banned, its ID is pushed to Redis set `auth:revoked_tokens:{userId}` with a 24-hour TTL (matching the maximum JWT token lifetime). The JWT authentication filter checks this key on every incoming HTTP request.
* **Transient Ticket Locks:** When an admin reviews a report ticket, a key `admin:review_lock:report:{reportId}` is written with a 15-minute TTL to signal active review to peer admins.

---

### 9.2 Relational Schema & Flyway Migration

Introduced via **Flyway migration V2** (`V2__admin_governance_and_audit.sql`):

```
users (1) ─────────── (many) audit_logs
companies (1) ─────── (many) jobs
users (1) ─────────── (many) violation_reports
users (1) ─────────── (many) user_account_bans
```

**Security DDL Hardening:**
```sql
-- Revoke UPDATE and DELETE permissions on audit_logs from app user
REVOKE UPDATE, DELETE ON audit_logs FROM guardian_app_user;
```

---

### 9.3 Read/Write Replica Splitting & Metrics Offloading

To protect customer-facing operations from being starved of database connections:
1. **Routing DataSource:** Configure Spring Boot with an `AbstractRoutingDataSource`.
   - Read-Write Transactions (`@Transactional(readOnly = false)`) route to the Primary PostgreSQL node.
   - Read-Only Transactions (`@Transactional(readOnly = true)`) for administrative reports, audit queries, and system metrics route to the Read Replica.
2. **Pre-aggregation Buffer:** Real-time CCU and request rates are sampled using Micrometer and stored in Redis. The Admin Health API reads from Redis rather than calculating aggregation queries over millions of transactional rows.

---

### 9.4 Kafka Messaging Topics

| Topic Name | Event Class | Key | Description |
|---|---|---|---|
| `notifications.email` | `EmailNotificationEvent` | `recipientEmail` | Dispatches verification approval/rejection and ban notification emails |
| `jobs.eviction` | `JobEvictionEvent` | `jobId` | Notifies search service to purge taken-down job from Elasticsearch |
| `audit.events` | `AuditEvent` | `adminId` | Optional downstream streaming to SIEM / cold-storage archive |

---

### 9.5 Configuration Constants

| Constant | Default Value | Config Key | Purpose |
|---|---|---|---|
| `REPORT_REVIEW_LOCK_TTL_SECONDS` | `900` | `app.admin.report-lock-ttl-seconds` | Expiration of peer admin review lock |
| `REVOKED_TOKEN_TTL_SECONDS` | `86400` | `app.admin.revoked-token-ttl-seconds` | Blacklist TTL matching JWT validity |
| `METRICS_CACHE_TTL_SECONDS` | `60` | `app.admin.metrics-cache-ttl-seconds` | Refresh rate of cached health snapshot |
| `MAX_AUDIT_LOG_PAGE_SIZE` | `100` | `app.admin.max-audit-page-size` | Prevents high memory consumption during log exports |

---

## 10. Non-Functional Requirements

| ID | Category | Requirement |
|---|---|---|
| **NFR-ADM-01** | **Security (RBAC)** | Global security filters **must** enforce role-level authorization (`role: "ADMIN"`) on all `/api/admin/**` endpoints prior to controller invocation. |
| **NFR-ADM-02** | **Security (Audit Immutability)** | All admin mutations **must** produce audit logs that cannot be altered or deleted, even by the originating admin. |
| **NFR-ADM-03** | **Security (Data Privacy)** | Administrative APIs **shall not** expose unencrypted passwords or internal private communications unless explicitly attached as evidence in an active violation ticket. |
| **NFR-ADM-04** | **Consistency (Optimistic Locking)** | Violation report resolutions **must** enforce optimistic concurrency control (`@Version`) to reject conflicting concurrent resolutions with HTTP `409 Conflict`. |
| **NFR-ADM-05** | **Performance (OLTP Isolation)** | Telemetry and analytics endpoints **must not** perform table scans on primary transactional tables. Queries must route to Read Replicas or Redis caches. |
| **NFR-ADM-06** | **Reliability (Session Revocation)** | Account ban token revocation **must** take effect across all distributed application nodes within < 100 milliseconds via Redis distributed set lookup. |
| **NFR-ADM-07** | **Observability** | All failed attempts to access admin endpoints without administrative privileges **must** be logged at `WARN` level with client IP, requested URI, and user ID for intrusion detection. |
| **NFR-ADM-08** | **Extensibility** | Background cron job triggers **must** execute asynchronously on an isolated thread pool to prevent blocking admin HTTP request worker threads. |
