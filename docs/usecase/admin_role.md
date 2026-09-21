# System Design Document: System Administrator (Admin)

---

## 1. Role Name & Scope of Authority

* **Role:** System Administrator (Admin)
* **Scope of Authority:** Holds the highest access privileges across the system (Super User). The Admin governs the lifecycle of all entities (Users, Companies, Jobs, Reports), intervenes in data states during violations, and monitors technical infrastructure (System Health, Traffic). However, to preserve data privacy, the Admin cannot view unencrypted sensitive data (such as passwords) or internal private corporate communications unless that data is submitted as evidence within an official Report.

---

## 2. Use Case List (Role: Admin)

| UC Code | Use Case Name | Description | Pre-condition | Post-condition | Priority |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **UC-ADM-01** | Verify Company Profile | Review and issue verification badges/legal entity verification for newly registered Recruiter/Company accounts. | Company has uploaded its Business Registration Certificate; status is `PENDING`. | Company status updates to `VERIFIED` or `REJECTED`. System sends an email notification. | High |
| **UC-ADM-02** | Resolve Violation Report | Review reports submitted by Candidates (e.g., fraudulent companies) or Recruiters (e.g., spam candidates) to make an enforcement decision. | An open report ticket exists with status `OPEN`. | Ticket status updates to `RESOLVED`. Corresponding enforcement actions (warnings, bans) can be triggered. | High |
| **UC-ADM-03** | Ban/Unban Account | Suspend or restore platform access for Candidates or Companies following community guideline violations or upon expiration of penalties. | Account exists in the system. | Account status updates to `BANNED` or `ACTIVE`. Current session tokens are revoked (if banned). | High |
| **UC-ADM-04** | Takedown Job Posting | Hide or remove job postings that contain prohibited content, false information, or expired postings causing system errors. | Job is currently in `PUBLISHED` status. | Job status changes to `SUSPENDED`. Job is purged from search caches (Elasticsearch/Redis). | Medium |
| **UC-ADM-05** | Monitor System Health | Track infrastructure metrics (CPU, RAM, DB connections) and incoming traffic (CCU, Request Rate). | Admin is logged into the Monitoring Dashboard. | Displays real-time charts/system logs without disrupting the primary database. | High |
| **UC-ADM-06** | Manage Background Jobs (Cron Jobs) | Manually trigger, inspect logs, or adjust frequencies of Cron jobs (e.g., expired post sweepers, data warehouse batch pipelines). | Background workers are running in the system. | Cron job status is updated (`Triggered`/`Paused`). Configuration changes are logged. | Medium |

---

## 3. Edge Cases & Security Risks (Architectural Considerations)

When designing APIs and Database Schemas for these use cases—especially within a SaaS Multi-tenant architecture—keep the following 4 critical risks in mind:

### 1. Cross-Tenant Data Leakage & Authorization Bypass (IDOR - Insecure Direct Object Reference)
* **Problem:** Admin endpoints (e.g., `DELETE /api/admin/companies/:id`) receive resource identifiers directly from client requests. If a Recruiter or regular user bypasses role-checking middleware, they could manipulate another tenant's data.
* **Mitigation:** Enforce strict Role-Based Access Control (RBAC) at the Global Guard level. Ensure all routes under `/api/admin/*` pass through middleware verifying that the JWT claims explicitly contain `role: "ADMIN"`.

### 2. State Inconsistency via Concurrency (Race Condition in Report Resolution)
* **Problem:** In multi-admin setups, two administrators might open the same report (UC-ADM-02) simultaneously and submit conflicting actions (e.g., one bans the user while the other rejects the report).
* **Mitigation:** Implement Optimistic Locking (tracking an incremental `version` column in the database) or a transient lock (Pessimistic Lock) when an admin starts reviewing a ticket. If Admin A updates the record first, Admin B's incoming request is rejected with an HTTP `409 Conflict` status.

### 3. "Who Watches the Watchers?" (Lack of Audit Trails)
* **Problem:** Administrators possess elevated permissions (banning organizations, dropping job records). If an admin rogue actor operates maliciously—or an admin credential is compromised—the system loses traceability and state recovery becomes difficult.
* **Mitigation:** Mandate an append-only `Audit_Logs` table (prohibiting `UPDATE` and `DELETE` queries). Every state-mutating request (`POST`, `PUT`, `DELETE`, `PATCH`) executed by the Admin role must be recorded with: `AdminID`, `Action`, `TargetID`, `Old_Payload`, `New_Payload`, `Timestamp`, and `IP_Address`.

### 4. Database Overload via Administrative Queries (Denial of Service - DoS)
* **Problem:** Heavy endpoints like UC-ADM-05 (System Analytics) or administrative batch exports often trigger full table scans. Executing these queries directly against the primary transactional database can lock critical tables and block customer traffic (e.g., candidates submitting job applications).
* **Mitigation:**
  * **Read/Write Replica Splitting:** Route analytical and report-heavy read operations to dedicated Read Replicas.
  * **Background Processing & Pre-aggregation:** Offload aggregation workloads to scheduled overnight cron jobs and ETL pipelines, storing precomputed metrics in a separate Data Warehouse or Analytics DB instead of computing aggregates on the fly during API requests.
