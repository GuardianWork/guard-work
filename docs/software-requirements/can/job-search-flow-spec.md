# Job Searching Flow - Software Requirements Specification (Backend)

| Field | Value |
|---|---|
| **Specification ID** | GW-FEAT-CAN-JOB-SEARCH-01 |
| **Project** | GuardWork |
| **Module** | Candidate Job Search |
| **File** | `job-search-flow-spec.md` |
| **Version** | 1.0.0 |
| **Date** | 2026-11-01 |
| **Last Updated** | 2026-11-01 |
| **Owner** | Backend - Candidate Job Search |
| **Approver** | Pending |
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

This document defines the backend software requirements for the GuardWork public job searching flow. It focuses on `GET /api/jobs/search`, the job content fields that search depends on, and the backend visibility rules that decide which postings can appear in search results.

### 1.2 Scope

| Sub-feature | Responsibility |
|---|---|
| **Public job search** | Anonymous or authenticated GET endpoint that filters PUBLISHED postings using keyword, location, industry, employment type, work arrangement, compensation, and deadline criteria. |
| **Searchable posting validation** | Only postings that are PUBLISHED, non-suspended, future-or-absent approved deadline, and owned by a verified, non-banned company are returned. |
| **Paginated public result** | Return `PageResponse<PublicDetail>` with canonical pagination bounds and deterministic sorting. |
| **Visibility-preserving detail boundary** | Search returns only public, visible job content and company identity; it does not expose staff-only or draft data. |

### 1.3 Technology Stack

| Layer | Technology |
|---|---|
| Runtime | Java 25 |
| Framework | Spring Boot 4.1.1 |
| Database | PostgreSQL 18 |
| Migrations | Flyway |
| ORM | Spring Data JPA |
| Search execution | PostgreSQL native SQL with `unaccent` |
| Pagination | Spring Data `Pageable`, `PaginationUtils` |

---

## 2. Overall Description

### 2.1 High-Level Flow

```
Client
  -> GET /api/jobs/search?...
  -> SecurityConfig / OnboardingAccessPolicy (anonymous GET allowed)
  -> JobSearchController
  -> JobSearchService
  -> JobPostingRepository.searchPublicJobIds (native SQL)
  -> JobPosting / JobRevision / Company lookups
  -> PageResponse<PublicDetail>
  -> ApiResponse<PageResponse<PublicDetail>> with Cache-Control: no-store
```

### 2.2 Lifecycle and State

Search does not change posting lifecycle state. It only reads postings whose persisted state is searchable at request time.

### 2.3 Response Envelope

All endpoints return the standard project envelope:

```json
{
  "status": 200,
  "message": "Success",
  "data": {
    "content": [],
    "pageNumber": 0,
    "pageSize": 5,
    "totalElements": 0,
    "totalPages": 0,
    "first": true,
    "last": true
  }
}
```

### 2.4 Public vs Protected Endpoints

`GET /api/jobs/search` is public. Anonymous requests are permitted by both `SecurityConfig` and `OnboardingAccessPolicy`.

---

## 3. Data Models

### 3.1 Searchable Posting Source

| Table | Relevant fields | Search behavior |
|---|---|---|
| `job_postings` | `id`, `company_id`, `status`, `staff_suspended`, `approved_deadline`, `published_revision_id`, `created_at` | Posting must be `PUBLISHED`, not staff-suspended, and have a future or absent approved deadline. |
| `job_posting_revisions` | `id`, `job_id`, `content` | Published revision content supplies title, description, location, optional industry, employment type, work arrangement, deadline, headcount, and compensation. |
| `companies` | `id`, `name`, `verification_status`, `is_banned` | Company must be `VERIFIED` and not banned. Company name is available to keyword search. |

### 3.2 Job Content Fields Used by Search

| Content field | Type | Mandatory in published posting | Search usage |
|---|---|---|---|
| `title` | text | yes | keyword containment |
| `description` | text | yes | keyword containment |
| `location` | text | yes | keyword and location containment |
| `industry` | text | no | optional industry containment |
| `employmentType` | enum text | yes | exact normalized enum match |
| `workArrangement` | enum text | yes | exact normalized enum match |
| `deadline` | timestamp text | yes | approved-deadline comparison |
| `compensation.disclosed` | boolean | yes when compensation is present | determines whether salary filters apply |
| `compensation.minimum` | number | yes when disclosed | salaryMax comparison |
| `compensation.maximum` | number | yes when disclosed | salaryMin comparison |

### 3.3 Database Extension and Query Support

PostgreSQL `unaccent` is enabled for diacritic-insensitive text matching. WSF-scale full-text search is not used.

---

## 4. Functional Requirements

| ID | Requirement |
|---|---|
| GW-JOB-SEARCH-01 | The system shall allow anonymous and authenticated callers to search published jobs. |
| GW-JOB-SEARCH-02 | The system shall support `keyword` as diacritic-insensitive containment against title, description, location, and company name. |
| GW-JOB-SEARCH-03 | The system shall support `location` as diacritic-insensitive containment against job location. |
| GW-JOB-SEARCH-04 | The system shall support `industry` as diacritic-insensitive containment against the optional job content industry value. |
| GW-JOB-SEARCH-05 | The system shall match `employmentType` and `workArrangement` against exact normalized enum values. |
| GW-JOB-SEARCH-06 | The system shall return postings whose approved deadline is on or before `deadline` when that filter is supplied. |
| GW-JOB-SEARCH-07 | The system shall apply `salaryMin` to postings whose disclosed maximum compensation is greater than or equal to the value. |
| GW-JOB-SEARCH-08 | The system shall apply `salaryMax` to postings whose disclosed minimum compensation is less than or equal to the value. |
| GW-JOB-SEARCH-09 | The system shall swap `salaryMin` and `salaryMax` when the supplied range is inverted. |
| GW-JOB-SEARCH-10 | The system shall return only searchable postings and sort them by `createdAt DESC, id DESC`. |
| GW-JOB-SEARCH-11 | The system shall normalize negative page numbers to zero. |
| GW-JOB-SEARCH-12 | The system shall bound page size to a default of 5 and maximum of 20. |
| GW-JOB-SEARCH-13 | The system shall guard page-size multiplication against integer overflow. |
| GW-JOB-SEARCH-14 | The system shall return `PageResponse<PublicDetail>` with `Cache-Control: no-store`. |

---

## 5. Flow Diagrams

### 5.1 Search Flow

```
Client
  |
  v
GET /api/jobs/search
  |
  v
JobSearchController.search(...)
  |
  v
JobSearchService.search(request, pageable)
  |-- normalize and escape text filters
  |-- validate enums and deadline syntax
  |-- bound pagination
  |-- query searchable posting IDs from PostgreSQL
  |-- load posting, revision, and company records
  |
  v
PageResponse<PublicDetail>
  |
  v
ApiResponse<PageResponse<PublicDetail>>
```

### 5.2 Decision Branches

```
Request
  |-- invalid enum -> 400
  |-- invalid deadline syntax -> 400
  |-- no matching postings -> successful empty page
  `-- matching postings -> load public content and company identity
```

---

## 6. Business Rules

| ID | Rule |
|---|---|
| BR-01 | `keyword`, `location`, and `industry` text matching shall be case-insensitive and diacritic-insensitive. |
| BR-02 | SQL `LIKE` wildcard characters in text filters shall be escaped before querying. |
| BR-03 | Only `PUBLISHED`, non-staff-suspended postings with future or absent approved deadlines are searchable. |
| BR-04 | Only postings for `VERIFIED`, non-banned companies are searchable. |
| BR-05 | `jobRevision.content` is the source of searchable job text and enum filters. |
| BR-06 | `experience` is not a supported search filter in this flow. |
| BR-07 | Search uses canonical `page` and `size` pagination; documentation-defined alias handling is governed by the job-search spec. |
| BR-08 | An empty matching result is a successful response, not an error. |

---

## 7. Error Catalogue

| Error Code | HTTP | Constant | Trigger |
|---|---|---|---|
| `40000` | 400 | `BAD_REQUEST` | Invalid `employmentType`, `workArrangement`, `deadline`, or other invalid request input |
| `40000` | 400 | `BAD_REQUEST` | Invalid pagination or deadline syntax when explicitly rejected by validation |
| `40478` | 404 | `JOB_NOT_FOUND` | Not applicable to the search list endpoint; job detail uses this error |
| `401` | 401 | `UNAUTHENTICATED` | Not applicable to the anonymous search route |

---

## 8. API Specification

### 8.1 GET /api/jobs/search - Search Public Jobs

Searches public, searchable postings.

**Query parameters**

| Name | Type | Required | Notes |
|---|---|---|---|
| `keyword` | string | no | diacritic-insensitive containment across title, description, location, and company name |
| `location` | string | no | diacritic-insensitive containment in job location |
| `industry` | string | no | diacritic-insensitive containment against optional job content `industry` |
| `employmentType` | enum string | no | `FULL_TIME`, `PART_TIME`, `CONTRACT`, `INTERNSHIP`, `TEMPORARY` |
| `workArrangement` | enum string | no | `ONSITE`, `HYBRID`, `REMOTE` |
| `deadline` | string | no | filter postings with approved deadline on or before this value |
| `salaryMin` | decimal | no | uses disclosed maximum compensation |
| `salaryMax` | decimal | no | uses disclosed minimum compensation |
| `page` | integer | no | default `0`; negative values normalized to `0` |
| `size` | integer | no | default `5`; bounds are `1..20` after normalization |

**Response `200 OK`**

```json
{
  "status": 200,
  "message": "Success",
  "data": {
    "content": [
      {
        "id": 1,
        "company": {
          "id": 1,
          "name": "Example Company"
        },
        "content": {},
        "publicationStatus": "PUBLISHED",
        "approvedDeadline": "2026-11-30T10:00:00Z",
        "applicationsAvailable": true,
        "applicationAvailabilityReason": null
      }
    ],
    "pageNumber": 0,
    "pageSize": 5,
    "totalElements": 1,
    "totalPages": 1,
    "first": true,
    "last": true
  }
}
```

---

## 9. Infrastructure Architecture

- The search query is executed natively against PostgreSQL.
- `unaccent` is enabled explicitly by Flyway migration.
- DI/CD deployables are not changed by this flow.
- `/api/jobs/search` is public through security and onboarding access policy rules.

---

## 10. Non-Functional Requirements

| ID | Requirement |
|---|---|
| NFR-01 | Text search shall use diacritic-insensitive matching without requiring a full-text search index. |
| NFR-02 | Query construction shall prevent SQL injection through parameterized statements. |
| NFR-03 | Invalid numeric or filter input shall return HTTP 400. |
| NFR-04 | Empty result pages shall be successful rather than treated as failure. |
| NFR-05 | Pagination calculations shall be guarded against integer overflow. |
