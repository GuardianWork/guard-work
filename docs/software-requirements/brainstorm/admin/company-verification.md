# [GW-BS-REC-01] Company Verification & Business License Inspection

| Field | Value |
|---|---|
| **Target User/Role** | Admin (Trust & Safety Reviewer) / Recruiter (Company Applicant) / System |
| **Parent Feature** | Admin Management & System Governance (KYB Onboarding Gate) |
| **Owner** | VyTrg |
| **Date** | 2026-10-04 |

# Problem

> What problem are we solving?

- **Fraudulent Recruitment & Scams:** Open marketplace job boards frequently suffer from bad actors creating fake employer profiles to post deceptive vacancies, extorting illegal upfront fees from job seekers (training deposits, interview fees, equipment costs).
- **Corporate Impersonation & PII Harvesting:** Malicious users register accounts under well-known corporate brand names, uploading publicly found registration papers to harvest sensitive candidate personal identifiable information (PII) and CVs.
- **Unregulated Employer Exposure:** Without a rigorous Know-Your-Business (KYB) gatekeeper, illegitimate companies can freely access candidate communications, publish listings, and undermine overall platform trust.
- **Concurrent Review Collisions & Audit Gaps:** When multiple administrators review verification queues simultaneously without optimistic concurrency control, race conditions can cause conflicting decisions. Without immutable database audit logs, rogue administrator actions cannot be tracked or audited.

# Solution & Goal

> What should be true after this feature is implemented?

- **Mandatory KYB Gatekeeper (UC-ADM-01):** Employers must submit their business registration details, a unique tax code, and an official Business Registration Certificate (*Giấy phép ĐKKD*) before being permitted to operate as a verified employer.
- **Administrative Verification Queue:** Platform administrators have a centralized, paginated, and filterable FIFO review queue (`GET /api/admin/companies/verifications`) to inspect company credentials and uploaded legal documents.
- **Deterministic Decision Execution:** Administrators can approve (`VERIFIED`) or reject (`REJECTED`) pending companies with a single transactional API (`PUT /api/admin/companies/{id}/verify`), where rejection mandates actionable feedback (`rejectionReason` ≥ 10 characters).
- **Optimistic Concurrency Control:** System enforces version checking (`expectedVersion` matching `companies.version`) to prevent concurrent modification by multiple admins, returning HTTP `409 Conflict` (`40022 CONCURRENT_MODIFICATION`) upon collisions.
- **Tamper-Proof Audit Logging:** Every verification decision (approval or rejection) atomically writes an append-only entry into `audit_logs` protected by database-level triggers against `UPDATE`, `DELETE`, and `TRUNCATE`.
- **Asynchronous Notification Pipeline:** Status transitions publish `EmailNotificationEvent` messages to Kafka (`notifications.email`) to notify recruiters of outcomes with detailed feedback.
- **Verified Privileges Activation:** Approved companies receive a verified status badge, elevated candidate search ranking, and unmasked candidate contact details, while unverified or rejected accounts face posting and data access restrictions.

# Scope

### In Scope

> Core behavior or capabilities included in this phase (MVP / Phase 1 — UC-ADM-01):

- **Company Profile & Document Submission:** Persistence of company registration metadata (name, unique tax code, S3/storage certificate URL) in `companies` table with initial status `PENDING`.
- **Admin Verification Queue Listing (`ADM-VER-01`):** Paginated listing of companies filterable by verification status (`PENDING`, `VERIFIED`, `REJECTED`, or `ALL`) with FIFO sorting (`createdAt,asc`).
- **Document & Credential Inspection (`ADM-VER-02`):** Administrative read access to company name, tax code, document storage URL, and submission timestamp.
- **Approval Decision (`ADM-VER-03`):** Setting `verification_status = 'VERIFIED'`, recording `verified_by` (Admin user ID) and `verified_at` (timestamp), and incrementing entity version.
- **Rejection Decision with Feedback (`ADM-VER-04`):** Requiring non-empty `rejectionReason` (10–1000 characters), setting `verification_status = 'REJECTED'`, and retaining the feedback in database.
- **Optimistic Concurrency Control (`ADM-VER-07`):** Enforcing `expectedVersion` matching `companies.version` on decision submission; aborting with HTTP `409 Conflict` on mismatch.
- **Immutable Audit Logging (`ADM-VER-06`):** Capturing decision action (`COMPANY_VERIFICATION`), admin ID, target ID, old/new payload diffs, reason, and client IP in append-only `audit_logs`.
- **Asynchronous Decision Notification (`ADM-VER-05`):** Publishing notification events to Apache Kafka topic `notifications.email` for email dispatch to the recruiter.
- **Recruiter Correction & Resubmission Flow:** Allowing rejected recruiters to correct submitted documents and tax code to transition back from `REJECTED` to `PENDING`.

### Out of Scope

> Capabilities planned for subsequent iterations (Phase 2 / Post-MVP):

- **Automated Document OCR & Forensics:** Automated OCR text extraction from uploaded business license documents and image tampering/EXIF forgery analysis.
- **Direct Government Registry API Integration:** Real-time lookup with the Vietnam National Business Registration Portal (`dangkykinhdoanh.gov.vn`) or General Department of Taxation.
- **Corporate Work Email Domain & DNS TXT Validation:** Enforcing domain match checks (`@company.com`) or DNS TXT ownership challenges for recruiter email domains.
- **Financial Authority & Micro-Deposits:** Tier 4 verification via corporate bank statement verification or micro-deposits.
- **Dynamic Peer-Admin Ticket Locks in UI:** Ephemeral Redis review lock (`admin:review_lock:company:{id}`) display for active admin presence in UI.

# Feature Requirements

> What can the user or system actually do?

| ID | Requirement / User Story | Priority |
|---|---|---|
| REQ-REC-01 | Recruiter can submit company name, unique tax code, and upload a Business Registration Certificate to request verification, placing the account in `PENDING` status. | High |
| REQ-REC-02 | Admin can retrieve a paginated FIFO queue of company verification requests filtered by status (`PENDING`, `VERIFIED`, `REJECTED`, `ALL`). | High |
| REQ-REC-03 | Admin can inspect submitted company profile information, tax code, and view/download the uploaded business registration document URL. | High |
| REQ-REC-04 | Admin can approve a pending company, updating `verification_status` to `VERIFIED`, recording `verified_by` and `verified_at`, and incrementing entity `version`. | High |
| REQ-REC-05 | Admin can reject a pending company by providing a mandatory `rejectionReason` (min 10 chars, max 1000 chars), setting `verification_status` to `REJECTED`. | High |
| REQ-REC-06 | System enforces optimistic concurrency control using `expectedVersion`, rejecting conflicting simultaneous reviews with HTTP `409 Conflict` (`40022 CONCURRENT_MODIFICATION`). | High |
| REQ-REC-07 | System atomically records an immutable, append-only audit trail in `audit_logs` capturing admin ID, IP address, old payload, and new payload diff. | High |
| REQ-REC-08 | System publishes an `EmailNotificationEvent` to Kafka topic `notifications.email` upon approval or rejection to asynchronously notify the employer. | High |
| REQ-REC-09 | Recruiter of a rejected company can inspect the rejection feedback, update documents/credentials, and resubmit for review. | High |
| REQ-REC-10 | System grants verified privileges (verified badge, elevated job search ranking, unmasked candidate contacts) to verified companies. | High |
| REQ-REC-11 | System automatically scans uploaded business certificates using OCR to extract tax code and detect digital stamp alterations. | Low |
| REQ-REC-12 | System integrates with National Business Registration API for automated tax code and operating status verification. | Medium |
| REQ-REC-13 | System validates recruiter corporate email domain against public WHOIS and DNS TXT records. | Medium |

# Business Logic

> What rules, constraints, or policies govern this feature?

| ID | Rule / Constraint | Notes |
|---|---|---|
| BR-REC-01 | **Eligible Verification Status (`BR-ADM-04`):** A company profile can only be approved (`VERIFIED`) or rejected (`REJECTED`) if its current status is `PENDING`. Non-pending mutations return HTTP 400 (`40010 COMPANY_NOT_PENDING`). | State machine constraint |
| BR-REC-02 | **Optimistic Concurrency Verification (`BR-ADM-04b`):** Any status mutation on `companies` requires validating that database `version` matches `expectedVersion`. On mismatch, transaction aborts and returns HTTP 409 (`40022 CONCURRENT_MODIFICATION`). | Prevents double decisions |
| BR-REC-03 | **Mandatory Rejection Feedback (`BR-ADM-05`):** If an admin rejects a verification request, a non-blank `rejectionReason` with at least 10 characters (and up to 1000 characters) is strictly mandatory. Must be null if approved. | Ensures actionable feedback |
| BR-REC-04 | **Tax Code Uniqueness:** Company tax code (`tax_code`) must be unique across all company records, enforced by a database `UNIQUE` constraint. Duplicate submissions return HTTP 409. | Multi-account fraud prevention |
| BR-REC-05 | **Role-Based Admin Access (`BR-ADM-01`):** All endpoints under `/api/admin/companies/**` require JWT token containing `role: "ADMIN"`. Unauthorized callers receive HTTP 401 or 403. | Security boundary |
| BR-REC-06 | **Immutable Audit Trail (`NFR-ADM-02`):** All verification approvals and rejections must atomically insert an append-only record into `audit_logs`. Database triggers prevent `UPDATE`, `DELETE`, or `TRUNCATE` operations on `audit_logs`. | Tamper-proof governance |
| BR-REC-07 | **Verified Privileges Activation (`BR-ADM-06`):** Marking a company as `VERIFIED` grants verified search ranking, unlocks unmasked candidate contact info, and awards the "Verified Employer" badge. | Core value proposition |
| BR-REC-08 | **Unverified Operational Restrictions:** Unverified companies (`PENDING`, `REJECTED`, `REGISTERED`) cannot view unmasked candidate contact details, and their posted jobs require administrative moderation. | Candidate data protection |
| BR-REC-09 | **Transactional Notification Delivery:** The database transaction updating company status and writing audit log must commit before or atomically with event emission to Kafka `notifications.email`. | Eventual consistency |
| BR-REC-10 | **Vietnamese Localization & Standards (`vi-VN`):** Feedback text, email notification templates, and admin audit entries must support UTF-8 Vietnamese text and Vietnam standard time (`Asia/Ho_Chi_Minh`). | GuardWork standard |

# Sub-tasks

- [ ] Complete UI wireframes for Admin Verification Queue and Side-by-Side Review Console
- [ ] Implement planned Flyway migration `V2__admin_governance_and_audit.sql` for `companies` table and append-only `audit_logs` table with immutability triggers
- [ ] Implement Admin verification listing endpoint (`GET /api/admin/companies/verifications`) with pagination, sorting, and status filtering
- [ ] Implement Admin verification decision endpoint (`PUT /api/admin/companies/{id}/verify`) with optimistic locking (`expectedVersion`)
- [ ] Implement append-only Audit Logging service (`audit_logs`) within the decision transaction boundary
- [ ] Configure Apache Kafka producer for topic `notifications.email` and create email notification templates for Approval and Rejection
- [ ] Implement Recruiter resubmission workflow for `REJECTED` companies
- [ ] Enforce candidate contact masking rules based on company verification status
- [ ] Write unit and integration tests covering verification approval, rejection validation, optimistic lock conflicts (HTTP 409), and duplicate tax codes
- [ ] Validate business rules and API error codes (`40010`, `40011`, `40000`, `40022`) with thesis team

# Changelogs

- 2026-10-04: Initial brainstorm draft created by `@VyTrg` based on [`docs/feat/admin/spec.md`](file:///home/vy/Documents/GuardianWork/guard-work/docs/feat/admin/spec.md).
