# Candidate Company Review — Software Requirements Specification (Backend)

| Field | Value |
|---|---|
| **Specification ID** | GW-FEAT-CAN-COMPANY-REVIEW-01 |
| **Project** | GuardWork |
| **Module** | Candidate Company Review and Discovery |
| **File** | `company-review-api-spec.md` |
| **Version** | 1.3.0 |
| **Date** | 2026-11-15 |
| **Last Updated** | 2026-11-16 |
| **Owner** | Backend — Candidate Company Review |
| **Approver** | Miku (task-session approval, 2026-11-15; 1.1.0 amendment approved in task session, 2026-11-16; 1.2.0 amendment approved in task session, 2026-11-16; 1.3.0 amendment approved in task session, 2026-11-16) |
| **Status** | Approved |

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Overall Description](#2-overall-description)
3. [Functional Requirements](#3-functional-requirements)
4. [Business Rules](#4-business-rules)
5. [Error Catalogue](#5-error-catalogue)
6. [API Specification](#6-api-specification)
7. [Infrastructure Architecture](#7-infrastructure-architecture)
8. [Validation](#8-validation)
9. [Amendment History](#9-amendment-history)

---

## 1. Introduction

### 1.1 Purpose

This specification defines the backend API for the candidate-facing company
discovery and company dossier screens introduced by GW-123.

### 1.2 Scope

| Sub-feature | Responsibility |
|---|---|
| **Public company search** | Anonymous keyword search over verified, non-banned companies, with a page of screen-ready company summaries. Companies stay discoverable without a live posting. |
| **Public company dossier** | Read one publicly listable company with a screen-ready summary derived from its visible postings, empty when it has none. |
| **Public company roles** | Paginated visible roles for one publicly listable company, empty when it has none, using canonical page and size bounds. |
| **Shared visibility** | Reuse the public job-search visibility boundary for draft, suspended, expired, unverified and banned postings. |

## 2. Overall Description

The backend reuses `GET /api/jobs/search` visibility logic through the job-search
service/repository. The company endpoints add company identity, grouping and screen summaries
without creating a second authorization or visibility implementation. Company listability is a
separate, narrower predicate (verified and not banned) so employers do not disappear from
discovery when their last posting expires.

## 3. Functional Requirements

| ID | Requirement |
|---|---|
| GW-CAN-COMPANY-01 | The system shall allow anonymous and authenticated users to search public companies by keyword. |
| GW-CAN-COMPANY-02 | The search shall return publicly listable companies, including companies with no publicly visible posting. |
| GW-CAN-COMPANY-03 | Search results shall include per-company summary facts derived from visible postings. |
| GW-CAN-COMPANY-04 | The dossier shall return one publicly listable company with a summary derived from its visible postings, empty when none are visible. |
| GW-CAN-COMPANY-05 | The roles endpoint shall paginate visible roles using canonical page and size bounds, and shall return an empty page for a publicly listable company with no visible roles. |
| GW-CAN-COMPANY-06 | Only an unknown company or a company that is not publicly listable shall return a company-not-found response. |
| GW-CAN-COMPANY-07 | Public reads shall send `Cache-Control: no-store`. |
| GW-CAN-COMPANY-08 | Company facts not published through visible postings, such as headquarters, working hours or expertise, shall not be invented by the backend. |
| GW-CAN-COMPANY-09 | The directory and dossier shall expose a company's public name from the company record so companies without visible postings have a stable name. |

## 4. Business Rules

| ID | Rule |
|---|---|
| BR-01 | Only `PUBLISHED`, non-staff-suspended postings with a future or absent approved deadline are searchable. |
| BR-02 | Only postings for `VERIFIED`, non-banned companies are searchable. |
| BR-03 | Keyword matching is case-insensitive and diacritic-insensitive through the shared job-search SQL. |
| BR-04 | Search summaries use all visible postings for a matched company, not only the matching posting. |
| BR-05 | Dossier summaries use all visible postings for the company; paginated role content uses canonical page and size. |
| BR-06 | Company profile mock or illustrative values remain client-side and never enter public API responses. |
| BR-07 | Each role in the public roles response carries `applicationCount`: the exact total of submitted applications for that posting, computed at read time from persisted applications. Postings without applicants report `0`, never a fabricated or partial value; no schema migration is required. |
| BR-08 | A company is publicly listable only when `verification_status = 'VERIFIED'` and `is_banned = FALSE`; only publicly listable companies appear in public company reads. |
| BR-09 | A summary with no visible postings reports `roleCount: 0`, empty collections, `disclosedSalaryCount: 0`, `headcountTotal: 0` and `deadlines: []`. No other company fact is substituted. |
| BR-10 | Directory ordering is visible role count descending, then company name case-insensitively ascending, then id ascending, so companies without visible roles sort after companies with roles. |

## 5. Error Catalogue

| Error Code | HTTP | Constant | Trigger |
|---|---:|---|---|
| `40400` | `404` | `COMPANY_NOT_FOUND` | Unknown company or a company that is not publicly listable (not `VERIFIED`, or banned). |
| `40000` | `400` | `BAD_REQUEST` | Reserved. Pagination bounds are normalized rather than rejected (page below 0 is clamped to 0; size above 20 is clamped to 20); currently not emitted. |

## 6. API Specification

### 6.1 `GET /api/companies/public`

Query parameter:

| Name | Type | Required | Notes |
|---|---|---:|---|
| `keyword` | string | no | Trimmed, diacritic-insensitive company/role text. |
| `page` | integer | no | Default `0`; negative values normalized. |
| `size` | integer | no | Default `20`; bounds `1..20`. |

Response: `PageResponse<CompanyDirectoryEntry>`: one entry per publicly listable company
(BR-08), including companies with no visible posting, ordered by BR-10. `summary.roleCount` is the
number of visible postings for that company and may be `0`; a summary without visible postings
follows BR-09. `totalElements` and `totalPages` count publicly listable companies, not companies
with roles.

### 6.2 `GET /api/companies/public/{companyId}`

Path variable: positive company id.

Query parameters: none.

Response: `CompanyDossierResponse`:

| Field | Type | Notes |
|---|---|---|
| `id` | number | Public company id. |
| `name` | string | Public company name from the company record. |
| `summary` | object | Company-level summary, derived from all visible postings; empty summary per BR-09 when none are visible. |

Unknown companies and companies that are not publicly listable return `40400 COMPANY_NOT_FOUND`.

### 6.3 `GET /api/companies/public/{companyId}/roles`

Path variable: positive company id.

Query parameters: canonical pagination `page` and `size`, same bounds as search.

Response: `PageResponse<CompanyRole>`: visible roles for the company, ordered by application
deadline (absent deadlines last), then by role id. Each role object carries
`applicationCount` (non-negative integer): the exact total of submitted applications for the
posting, `0` when nobody applied yet. A publicly listable company with no visible posting returns
an empty page (`content: []`, `totalElements: 0`, `totalPages: 0`, `first: true`, `last: true`); a
`page` beyond the last role also returns an empty page rather than an error. Unknown companies and
companies that are not publicly listable return `40400 COMPANY_NOT_FOUND`.

## 7. Infrastructure Architecture

- No schema migration, dependency or notification integration is added.
- Public endpoints are permitted for anonymous and authenticated callers, before the
  protected company management routes and before the recruiting-email gate.
- Public company reads share the job-search module rather than duplicating visibility SQL.
- Company listability (BR-08) is evaluated on the company record; posting-dependent facts still
  come from the shared public posting predicate, so the two cannot diverge.

## 8. Validation

Backend checks were run against PostgreSQL-compatible test infrastructure and the
existing job-search regression suite.

## 9. Amendment History

### 1.3.0 — 2026-11-16

- Public company reads now start from publicly listable companies (`VERIFIED`, not banned), so
  employers without a visible posting are discoverable and their dossier and roles endpoints
  return `200` with empty content instead of `404`.
- Replaced GW-CAN-COMPANY-02 and GW-CAN-COMPANY-06; added GW-CAN-COMPANY-09 so the public name
  comes from the company record.
- New BR-08 (listability), BR-09 (empty summary) and BR-10 (directory ordering).
- `40400 COMPANY_NOT_FOUND` narrowed to unknown or non-listable companies.
- Out-of-range role pages return an empty page instead of failing.
- No schema migration, new dependency, authorization or pagination change. Response sets widen
  and previously `404` companies now return `200`; the candidate UI must render `roleCount: 0`
  entries and empty states. Task-session approval by Miku, 2026-11-16.

### 1.2.0 — 2026-11-16

- §6.3 roles response now carries `applicationCount` per role: the exact total of submitted
  applications for the posting (`0` when none), computed at read time without a schema migration.
  Backs the GW-123 candidate-facing featured-posting cascade tier ("most applicants").
- New business rule BR-07 records the exact-count contract and its source of truth.
- No authorization, pagination, visibility or error-catalogue changes. Backward-compatible
  addition of an optional response field. Task-session approval by Miku, 2026-11-16.

### 1.1.0 — 2026-11-16

- Aligned §6 with the implemented public read surface: the dossier (`6.2`) returns
  id, name and the company-level summary only; visible roles moved to a new
  paginated endpoint (`6.3`), matching GW-CAN-COMPANY-05.
- Public company reads are permitted for anonymous and authenticated callers,
  before the recruiting-email gate (§7), per task-session approval (Miku, 2026-11-16).
- Annotated the `40000 BAD_REQUEST` catalogue entry as reserved: pagination bounds
  are normalized, not rejected (§5).
- Requirements GW-CAN-COMPANY-04 and GW-CAN-COMPANY-05 reworded accordingly; no
  other requirements changed.
