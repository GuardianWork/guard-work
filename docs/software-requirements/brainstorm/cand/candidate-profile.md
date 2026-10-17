# [GW-BS-CAND-01] Candidate Profile Management

| Field | Value                                   |
|---|-----------------------------------------|
| **Target User/Role** | Candidate                               |
| **Parent Feature** | Candidate Role (`docs/feat/candidates`) |
| **Owner** | Chiko                                    |
| **Date** | 2026-10-07                              |

# Problem
> Candidates registering on GuardianWork need a centralized, structured, and privacy-compliant profile to present their skills, work history, and contact details to potential employers without exposing sensitive personal data prematurely or violating Vietnamese data protection regulations (Law 91/2025/QH15).

# Solution & Goal
> Provide a streamlined candidate profile management module that:
> 1. Automatically initializes a candidate profile upon user registration.
> 2. Allows candidates to easily update contact info, avatar URL, skills tags, and experience/education summaries.
> 3. Enforces profile privacy controls (`is_public`) and sensitive contact masking for unverified employer accounts.
> 4. Prepares a standardized backend API (`GET /api/v1/candidate/profile`, `PUT /api/v1/candidate/profile`) for frontend integration and job application attachment.

# Scope

### In Scope
- Automatic skeleton profile creation upon candidate registration (`ROLE_CANDIDATE`).
- Fetching candidate profile details via `GET /api/v1/candidate/profile`.
- Updating profile information via `PUT /api/v1/candidate/profile` (full name, phone number, avatar URL, skill tags, experience summary, education summary, profile visibility).
- Simple binary profile visibility control (`is_public`: `true` = searchable by employers in talent database; `false` = hidden from talent search, viewable only by recruiters when candidate explicitly applies to a job).
- Skill tag management: JSON array of string skill tags (max 30 items, trimmed, normalized, deduplicated).
- Vietnamese UI copy support (`vi-VN`) and contact masking for unverified employers.

### Out of Scope
- Direct binary S3 avatar file upload handling (deferred to Phase 2; MVP uses external URL string input `avatar_url`).
- Granular sub-tables for individual work experience and education items (deferred to Phase 2; MVP uses rich text summaries `experience_summary` and `education_summary`).
- Automatic CV parsing or AI skill extraction (Phase 2).
- Candidate self-service profile deletion cascade / right to erasure (Phase 2 privacy scope).

# Feature Requirements

| ID | Requirement / User Story | Priority |
|---|---|---|
| REQ-CAND-01 | As a newly registered candidate, the system **shall** automatically create a skeleton profile record (`candidate_profiles`) populated with my full name from registration. | High |
| REQ-CAND-02 | As a candidate, I **shall** be able to view my candidate profile via `GET /api/v1/candidate/profile`. | High |
| REQ-CAND-03 | As a candidate, I **shall** be able to update my profile details (full name, phone number, avatar URL, skills, experience summary, education summary, and `is_public` status) via `PUT /api/v1/candidate/profile`. | High |
| REQ-CAND-04 | As a candidate, I **shall** be able to manage up to 30 skill tags in my profile, formatted as a normalized string array `["Java", "Spring Boot", "PostgreSQL"]`. | Medium |
| REQ-CAND-05 | As a candidate, I **shall** be able to toggle my profile visibility (`is_public`). When set to `false`, my profile **must** be hidden from employer search lists, but remain accessible to employers of jobs I explicitly apply to. | Medium |
| REQ-CAND-06 | As an unverified employer viewing a candidate profile, the system **shall** mask candidate contact details (email and phone number) until employer verification is complete. | High |

# Business Logic

| ID | Rule / Constraint | Notes |
|---|---|---|
| BR-CAND-01 | A candidate profile (`candidate_profiles`) has a 1:1 strict relationship with a `users` account (`user_id` FK, UNIQUE constraint). | Core database constraint |
| BR-CAND-02 | User interface copy and error messages must support Vietnamese (`vi-VN`). | GuardWork standard |
| BR-CAND-03 | `skills` must be stored as JSONB array of strings, capped at a maximum of 30 tags. Each tag must be trimmed of whitespace and deduplicated case-insensitively. | Data validation rule |
| BR-CAND-04 | `avatar_url` must be a valid HTTPS URL string (max 512 characters). Binary image file uploads are deferred to Phase 2. | MVP scope rule |
| BR-CAND-05 | Profile visibility `is_public` defaults to `true`. When `is_public = false`, the candidate record is excluded from recruiter directory search queries. | Privacy rule |
| BR-CAND-06 | Contact info (`email`, `phone_number`) must be masked (`ngu***@gmail.com`, `0912***89`) when retrieved by an unverified employer (`EMPLOYER` role with unverified company status). | Law 91/2025/QH15 compliance |

# Sub-tasks

- [ ] Validate business rules and schema changes with team
- [ ] Create Flyway DB migration script for `candidate_profiles` table updates (`is_public` column)
- [ ] Implement skeleton profile auto-creation on `ROLE_CANDIDATE` registration
- [ ] Implement `GET /api/v1/candidate/profile` and `PUT /api/v1/candidate/profile` Spring Boot endpoints & DTOs
- [ ] Implement skill array normalization & validation annotations
- [ ] Add unit & integration test coverage for candidate profile CRUD and privacy masking

# Changelogs

- 2026-10-07: Initial brainstorm draft created by `@chiko`.
