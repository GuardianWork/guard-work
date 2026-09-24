# Company Profile Verification: Business Analysis & Architecture Guide

| Field | Value |
|---|---|
| **Project** | GuardianWork |
| **Domain** | Trust & Safety / Admin Governance |
| **Feature** | Company Profile Verification (`UC-ADM-01`) |
| **File** | `docs/company_verification.md` |
| **Version** | 1.0.0 |
| **Date** | 2026-09-21 |
| **Status** | Approved Specification Reference |

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Real-World Multi-Tier Verification Model](#2-real-world-multi-tier-verification-model)
3. [Core Actors & Responsibilities](#3-core-actors--responsibilities)
4. [Lifecycle & State Machine](#4-lifecycle--state-machine)
5. [End-to-End Verification Pipeline](#5-end-to-end-verification-pipeline)
6. [Trust & Safety Operations Console](#6-trust--safety-operations-console)
7. [The 5-Point Document Inspection Checklist](#7-the-5-point-document-inspection-checklist)
8. [Common Fraud Vectors & Risk Mitigations](#8-common-fraud-vectors--risk-mitigations)
9. [GuardianWork Technical Implementation Mapping](#9-guardianwork-technical-implementation-mapping)

---

## 1. Executive Summary

Company profile verification is a foundational Trust & Safety capability for GuardianWork. In recruitment and marketplace ecosystems, verifying a company is not merely a cosmetic administrative approval—it is the primary defense line against:
- **Fraudulent Recruitment & Upfront Fee Scams:** Fake recruiters charging candidates for interviews, uniforms, or training fees.
- **Corporate Identity Theft & Impersonation:** Scammers falsely registering well-known brand names using publicly available corporate documents to lure job seekers.
- **Phishing & Data Harvesting:** Attackers seeking candidate personal identifying information (PII), CVs, and contact details.

This document details both the **real-world business operations** practiced by leading platforms (e.g., LinkedIn, Indeed, Upwork, TopCV, Stripe KYB) and the **concrete technical architecture** implemented in GuardianWork.

---

## 2. Real-World Multi-Tier Verification Model

Leading platforms utilize a tiered trust framework to balance fraud prevention with user onboarding friction:

```
┌────────────────────────────────────────────────────────────────────────┐
│ Tier 4: Financial & Legal Representative Authority (Highest Trust)     │
│ - Corporate bank account validation (bank statement / micro-deposits)  │
│ - Power of Attorney (Giấy ủy quyền) for recruiter/HR staff            │
├────────────────────────────────────────────────────────────────────────┤
│ Tier 3: Legal Certificate & Document Forensics (KYB / Manual Review)   │
│ - Official Business Registration Certificate (Giấy phép ĐKKD)          │
│ - Document tampering detection (OCR, EXIF metadata, seal validation)   │
├────────────────────────────────────────────────────────────────────────┤
│ Tier 2: National Government Registry Cross-Check                      │
│ - Tax portal lookup (National Business Registry / IRS / Masothue)      │
│ - Confirmation that legal operating status is "ACTIVE / OPERATING"     │
├────────────────────────────────────────────────────────────────────────┤
│ Tier 1: Digital Identity & Domain Ownership (Automated, Low Friction)  │
│ - Corporate email domain matching (e.g., @company.com vs @gmail.com)   │
│ - DNS record verification (TXT record) or domain WHOIS verification    │
└────────────────────────────────────────────────────────────────────────┘
```

### Detailed Breakdown of the 4 Tiers

1. **Tier 1 — Digital Identity & Domain Ownership (Automated):**
   - **Corporate Email Enclosure:** Legitimate businesses recruit using corporate domain emails (e.g., `recruiter@guardianwork.com`). Free or disposable domains (`@gmail.com`, `@yahoo.com`, temp mail) are either blocked from posting or routed directly to stricter manual review.
   - **Domain Validation:** Checks MX records, domain age (domains registered within the last 30 days carry elevated risk), and SSL certificate authenticity.

2. **Tier 2 — National Government Registry Cross-Check:**
   - **Official Registry Confirmation:** Every jurisdiction maintains an authoritative corporate database (e.g., Vietnam National Business Registration Portal `dangkykinhdoanh.gov.vn`, General Department of Taxation `gdt.gov.vn`, UK Companies House, US Secretary of State).
   - **Active Status Check:** Validates that the entity is currently active, legally authorized to operate, and not dissolved or in bankruptcy.

3. **Tier 3 — Document & Identity Forensics (KYB Manual Review):**
   - **Document Inspection:** Reviews official business licenses, articles of incorporation, or tax certificates.
   - **Tamper Detection:** Analyzes digital signatures, official red stamps, font consistency, and metadata to ensure documents have not been modified in photo editors.

4. **Tier 4 — Representative Authority & Identity Proof:**
   - **Preventing Brand Impersonation:** When a recruiter is not the registered CEO or Legal Representative listed on official filings, an official Power of Attorney (*Giấy ủy quyền*) or employment verification is required to confirm they are authorized to act on behalf of the company.

---

## 3. Core Actors & Responsibilities

| Actor | Type | Responsibilities |
|---|---|---|
| **Recruiter / Employer** | External User | Submits company profile details, tax ID, and uploads clear, unedited legal business documents. |
| **Trust & Safety Admin** | Internal Staff (`ADMIN`) | Reviews submitted applications against public registries, inspects document integrity, and records approval or detailed rejection feedback. |
| **Notification Worker** | Background Consumer | Listens to verification events from Apache Kafka and sends automated email notifications with decision details. |
| **System Security Filter** | Middleware Guard | Enforces Role-Based Access Control (RBAC), prevents IDOR bypasses, and records immutable audit logs. |

---

## 4. Lifecycle & State Machine

```
[REGISTERED]
     │
     └── Recruiter uploads certificate & tax code ──► [PENDING]
                                                         │
                                                         ├── Admin approves ──► [VERIFIED] (Badge granted, full features unlocked)
                                                         │
                                                         └── Admin rejects  ──► [REJECTED] (Requires correction & re-submission)
                                                                                   │
                                                                                   └── Recruiter re-submits docs ──► [PENDING]
```

### State Definitions

- **`REGISTERED`:** Initial state upon recruiter account creation. The company profile is incomplete; job posting and candidate search capabilities are restricted.
- **`PENDING`:** The company has submitted its tax code and registration certificate. It enters the administrator's review queue.
- **`VERIFIED`:** An administrator confirmed legal legitimacy. The company receives a "Verified Employer" badge, elevated search rankings, and full communication access.
- **`REJECTED`:** The application was declined due to illegible documents, tax code mismatch, or unverified representative authority. Requires correction and resubmission.

---

## 5. End-to-End Verification Pipeline

```
Admin Client                Admin API / Service             PostgreSQL                   Redis / Kafka
     │                               │                          │                              │
(1)  │── GET /companies/verifications ─────────────────────────►│                              │
     │◄── Paginated PENDING list ────│                          │                              │
     │                               │                          │                              │
(2)  │── [Inspects Certificate URL, Tax Code, Company Details]  │                              │
     │                               │                          │                              │
(3)  │── PUT /companies/{id}/verify ─►│                          │                              │
     │   { status, rejectionReason } │                          │                              │
     │                               │── 1. Validate status/payload                            │
     │                               │── 2. Check optimistic lock (version)                    │
     │                               │── 3. Execute DB Transaction ───────────────────────────►│
     │                               │      - Update companies (status, verified_by/at)        │
     │                               │      - Insert audit_logs (append-only)                  │
     │                               │◄── Commit Transaction ──────────────────────────────────│
     │                               │                                                         │
     │                               │── 4. Publish Event (notifications.email) ──────────────►│ (Kafka)
     │◄── 200 OK (Decision Recorded)─│                                                         │
```

### Step-by-Step Workflow

1. **Queue Retrieval:**
   - Admin accesses the verification queue via `GET /api/admin/companies/verifications?status=PENDING&page=0&size=20`.
   - Records are sorted `createdAt ASC` (FIFO queue) to uphold Service Level Agreements (SLAs).

2. **Document & Profile Review:**
   - The admin cross-references the submitted profile with the uploaded certificate and public tax registry.

3. **Decision Execution:**
   - The admin submits a decision via `PUT /api/admin/companies/{id}/verify`:
     - **Approval:** `{"status": "VERIFIED", "rejectionReason": null}`
     - **Rejection:** `{"status": "REJECTED", "rejectionReason": "The tax code on the certificate does not match the profile..."}`

4. **Atomic Database Mutation:**
   - Validates that the entity is in `PENDING` status.
   - Evaluates optimistic locking (`version` column) to ensure no other admin modified the record.
   - Updates company verification fields and inserts an immutable record into `audit_logs` within a single `@Transactional` boundary.

5. **Asynchronous Notification Delivery:**
   - Publishes an `EmailNotificationEvent` to Kafka topic `notifications.email`.
   - Email worker delivers confirmation or rejection instructions to the recruiter.

---

## 6. Trust & Safety Operations Console

In production environments, the administrative review interface provides a side-by-side comparison between submitted user data and official records:

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ ADMIN TRUST & SAFETY CONSOLE — TICKET #VER-8492                                       │
├──────────────────────────────────────┬─────────────────────────────────────────────────┤
│ SUBMITTED APPLICATION                │ OFFICIAL GOVERNMENT REGISTRY DATA (LOOKUP)      │
├──────────────────────────────────────┼─────────────────────────────────────────────────┤
│ Company Name: Guardian Tech JSC      │ Legal Name: CÔNG TY CỔ PHẦN GUARDIAN TECH       │
│ Tax Code:     0316892341             │ Status:     ACTIVE / OPERATING (Đang hoạt động) │
│ Address:      123 D1, Binh Thanh, HCMC│ Reg Address: 123 D1, Binh Thanh, HCMC           │
│ Rep:          Nguyen Van A           │ Legal Rep:  Nguyen Van A                        │
├──────────────────────────────────────┴─────────────────────────────────────────────────┤
│ UPLOADED DOCUMENT PREVIEW                                                              │
│ [ PDF Viewer: Giay_Phep_DKKD_GuardianTech.pdf ]                                        │
│ - OCR Confidence: 98.4% (Matched Tax Code, Legal Rep, and Address)                     │
│ - Tampering Risk Score: LOW (0.02)                                                     │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ DECISION PANEL                                                                         │
│ [  APPROVE & ISSUE VERIFIED BADGE  ]                                                  │
│                                                                                        │
│ [  REJECT APPLICATION  ]  Reason Category: [ Invalid Document ▼ ]                      │
│   Detailed Feedback for Recruiter:                                                     │
│   [ "The uploaded registration certificate is expired. Please upload the latest..."  ] │
│                                                                                        │
│ [  REQUEST MORE INFO  ]  Required Items: [☑ Power of Attorney] [☑ Work Email]          │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. The 5-Point Document Inspection Checklist

Before an administrator marks any company profile as `VERIFIED`, all 5 criteria must pass:

| Checkpoint | What to Verify | Failure Action |
|---|---|---|
| **1. Tax Code Match** | Submitted `tax_code` matches the certificate and the official government business registry. | Reject (`TAX_CODE_MISMATCH`) |
| **2. Legal Name Match** | Company legal name matches letter-for-letter against official government records. | Reject (`NAME_MISMATCH`) |
| **3. Operating Status** | Public registry confirms status is currently **ACTIVE / OPERATING** (not suspended or liquidated). | Reject (`COMPANY_INACTIVE`) |
| **4. Document Authenticity** | Official seal/stamp and signature are present, sharp, and show no digital alteration artifacts. | Reject (`DOCUMENT_ALTERED`) |
| **5. Authorization Link** | Submitting user's identity is linked to the legal representative (by name, corporate email domain, or authorization letter). | Request Info / Reject (`UNAUTHORIZED_REPRESENTATIVE`) |

---

## 8. Common Fraud Vectors & Risk Mitigations

### 1. Corporate Identity Theft (Impersonation Scams)
- **Attack Vector:** Scammers register under a well-known brand (e.g., "Shopee", "FPT Software") and upload publicly available business licenses downloaded from the web.
- **Mitigation:**
  - Enforce work email domain verification (e.g., applicant must confirm a link sent to an `@fpt.com` or `@shopee.com` address).
  - If a free email is used, mandate an official signed and stamped Authorization Letter (*Giấy ủy quyền*).

### 2. Concurrent Admin Modification (Race Condition)
- **Problem:** Two admins simultaneously review the same ticket. Admin A approves while Admin B rejects, causing inconsistent state and conflicting notification emails.
- **Mitigation:**
  - Database **Optimistic Locking** (`version` column).
  - The first transaction increments `version` (`0 -> 1`). The subsequent transaction encounters a version mismatch and fails with HTTP `409 CONFLICT`.

### 3. Rogue Admin / Collusion ("Who Watches the Watchers?")
- **Problem:** A rogue internal administrator approves a fraudulent entity in exchange for kickbacks.
- **Mitigation:**
  - Mandatory, append-only [`audit_logs`](file:///home/vy/Documents/GuardianWork/guard-work/docs/software-requirements/admin_software_requirements.md#31-auditlog-postgresql--audit_logs-table) table.
  - PostgreSQL role permissions explicitly revoke `UPDATE` and `DELETE` on `audit_logs`.
  - Captures `admin_id`, `ip_address`, `timestamp`, and complete `old_payload`/`new_payload` diffs.

### 4. Vague Rejection Feedback
- **Problem:** Rejection without actionable feedback leads to repeated erroneous resubmissions and customer support overload.
- **Mitigation:**
  - Business Rule [`BR-ADM-05`](file:///home/vy/Documents/GuardianWork/guard-work/docs/software-requirements/admin_software_requirements.md#6-business-rules): Mandates `rejectionReason` with a minimum of 10 characters for any rejection payload.

---

## 9. GuardianWork Technical Implementation Mapping

This section connects the business requirements to the implementation specifications in [`docs/software-requirements/admin_software_requirements.md`](file:///home/vy/Documents/GuardianWork/guard-work/docs/software-requirements/admin_software_requirements.md).

### Database Schema Reference (`companies` table)

```sql
CREATE TABLE companies (
    id                          BIGSERIAL PRIMARY KEY,
    name                        VARCHAR(255) NOT NULL,
    tax_code                    VARCHAR(50)  NOT NULL UNIQUE,
    registration_certificate_url TEXT         NOT NULL,
    verification_status         VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    rejection_reason            TEXT,
    verified_by                 BIGINT REFERENCES users(id),
    verified_at                 TIMESTAMPTZ,
    is_banned                   BOOLEAN      NOT NULL DEFAULT false,
    version                     BIGINT       NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_companies_verification_status ON companies(verification_status);
```

### API Endpoint Contracts

- **List Verification Queue:** `GET /api/admin/companies/verifications?status=PENDING&page=0&size=20`
- **Execute Verification Decision:** `PUT /api/admin/companies/{id}/verify`
  ```json
  {
    "status": "VERIFIED | REJECTED",
    "rejectionReason": "Detailed reason if rejected, null if verified"
  }
  ```

### Associated Error Codes

| Error Code | HTTP Status | Constant | Trigger |
|---|---|---|---|
| `40010` | 400 | `COMPANY_NOT_PENDING` | Company status is already `VERIFIED` or `REJECTED` |
| `40011` | 404 | `COMPANY_NOT_FOUND` | Company ID does not exist |
| `40000` | 400 | `INVALID_INPUT` | Rejection reason missing or shorter than 10 characters |
| `40900` | 409 | `CONCURRENT_MODIFICATION` | Version conflict between concurrent administrators |
| `40300` | 403 | `FORBIDDEN` | Request executed without valid `ADMIN` role claim |
