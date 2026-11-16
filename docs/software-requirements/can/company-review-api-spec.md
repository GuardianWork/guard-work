# Candidate Company Review — Software Requirements Specification (Backend)

| Field | Value |
|---|---|
| **Specification ID** | GW-FEAT-CAN-COMPANY-REVIEW-01 |
| **Project** | GuardWork |
| **Module** | Candidate Company Review and Discovery |
| **File** | `company-review-api-spec.md` |
| **Version** | 1.0.0 |
| **Date** | 2026-11-15 |
| **Last Updated** | 2026-11-15 |
| **Owner** | Backend — Candidate Company Review |
| **Approver** | Miku (task-session approval, 2026-11-15) |
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

---

## 1. Introduction

### 1.1 Purpose

This specification defines the backend API for the candidate-facing company
discovery and company dossier screens introduced by GW-123.

### 1.2 Scope

| Sub-feature | Responsibility |
|---|---|
| **Public company search** | Anonymous keyword search over companies with visible public postings, with a page of screen-ready company summaries. |
| **Public company dossier** | Read one company and its visible open roles with screen-ready summaries and paginated roles. |
| **Shared visibility** | Reuse the public job-search visibility boundary for draft, suspended, expired, unverified and banned postings. |

## 2. Overall Description

The backend reuses `GET /api/jobs/search` visibility logic through the job-search
service/repository. The company endpoints add grouping and screen summaries without
creating a second authorization or visibility implementation.

## 3. Functional Requirements

| ID | Requirement |
|---|---|
| GW-CAN-COMPANY-01 | The system shall allow anonymous and authenticated users to search public companies by keyword. |
| GW-CAN-COMPANY-02 | The search shall return companies with at least one publicly visible posting. |
| GW-CAN-COMPANY-03 | Search results shall include per-company summary facts derived from visible postings. |
| GW-CAN-COMPANY-04 | The dossier shall return one public company and its visible roles. |
| GW-CAN-COMPANY-05 | The dossier shall paginate visible roles using canonical page and size bounds. |
| GW-CAN-COMPANY-06 | A company with no publicly visible posting shall return a company-not-found response. |
| GW-CAN-COMPANY-07 | Public reads shall send `Cache-Control: no-store`. |
| GW-CAN-COMPANY-08 | Company facts not published through visible postings, such as headquarters, working hours or expertise, shall not be invented by the backend. |

## 4. Business Rules

| ID | Rule |
|---|---|
| BR-01 | Only `PUBLISHED`, non-staff-suspended postings with a future or absent approved deadline are searchable. |
| BR-02 | Only postings for `VERIFIED`, non-banned companies are searchable. |
| BR-03 | Keyword matching is case-insensitive and diacritic-insensitive through the shared job-search SQL. |
| BR-04 | Search summaries use all visible postings for a matched company, not only the matching posting. |
| BR-05 | Dossier summaries use all visible postings for the company; paginated role content uses canonical page and size. |
| BR-06 | Company profile mock or illustrative values remain client-side and never enter public API responses. |

## 5. Error Catalogue

| Error Code | HTTP | Constant | Trigger |
|---|---:|---|---|
| `40400` | `404` | `COMPANY_NOT_FOUND` | Unknown company or no visible public posting. |
| `40000` | `400` | `BAD_REQUEST` | Invalid pagination bounds. |

## 6. API Specification

### 6.1 `GET /api/companies/public`

Query parameter:

| Name | Type | Required | Notes |
|---|---|---:|---|
| `keyword` | string | no | Trimmed, diacritic-insensitive company/role text. |
| `page` | integer | no | Default `0`; negative values normalized. |
| `size` | integer | no | Default `20`; bounds `1..20`. |

Response: `PageResponse<CompanyDirectoryEntry>`.

### 6.2 `GET /api/companies/public/{companyId}`

Path variable: positive company id.

Query parameters: canonical pagination `page` and `size`, same bounds as search.

Response: `CompanyDossierResponse`:

| Field | Type | Notes |
|---|---|---|
| `id` | number | Public company id. |
| `name` | string | Company name from visible postings. |
| `summary` | object | Company-level summary. |
| `roles` | object | `PageResponse<CompanyRole>` for visible roles. |

## 7. Infrastructure Architecture

- No schema migration, dependency or notification integration is added.
- Public endpoints are permitted for anonymous callers and for authenticated users
  whose account is eligible before the protected company management routes.
- Public company reads share the job-search module rather than duplicating visibility SQL.

## 8. Validation

Backend checks were run against PostgreSQL-compatible test infrastructure and the
existing job-search regression suite.
