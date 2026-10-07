# [GW-BS-CAND-02] Candidate CV Upload & Management

| Field | Value |
|---|---|
| **Target User/Role** | Candidate |
| **Parent Feature** | Candidate Role (`docs/feat/candidates`) |
| **Owner** | Thesis Team |
| **Date** | 2026-10-07 |

# Problem
> Candidates need a reliable, secure, and privacy-compliant way to upload, store, and attach CV documents (PDF/Word) to job applications without exposing sensitive personal data (e.g., national ID/CCCD, bank details) or exposing raw public cloud storage URLs.

# Solution & Goal
> Provide a Cloudinary-backed CV upload and management mechanism that:
> 1. Issues Cloudinary signed upload URLs via Spring Boot backend (`POST /api/v1/candidate/cvs/upload-url`) to enable secure direct file uploads from the frontend.
> 2. Registers CV metadata (`file_name`, `file_size`, `content_type`, `public_id`) in PostgreSQL `candidate_cvs` table (`POST /api/v1/candidate/cvs`).
> 3. Restricts document viewing to short-lived signed URLs (TTL 15 minutes) for authorized candidates and job application recruiters.
> 4. Enforces file constraints (PDF, DOC, DOCX, max 10MB, cap at 5 CVs per candidate) and mandatory privacy consent UI checks (Law 91/2025/QH15).

# Scope

### In Scope
- Requesting signed Cloudinary upload parameters and signatures from Spring Boot backend (`POST /api/v1/candidate/cvs/upload-url`).
- Registering completed CV upload metadata in PostgreSQL (`POST /api/v1/candidate/cvs`).
- Fetching a candidate's uploaded CV list (`GET /api/v1/candidate/cvs`).
- Generating short-lived signed viewing/download URLs (TTL 15 min) (`GET /api/v1/candidate/cvs/{id}/download-url`).
- Mandatory privacy warning notice and explicit consent checkbox on the frontend upload modal.
- Automatic initial default CV setting (`is_default = true` for the candidate's first uploaded CV).
- Enforcing file type validation (PDF, DOC, DOCX), max size (10 MB), and candidate CV count quota (max 5 active CVs).

### Out of Scope
- Direct file deletion, title renaming, or manual default CV toggling (deferred strictly to Phase 2 per SRS spec).
- Automated backend OCR or regex content scanning for sensitive data (Phase 2).
- Native PDF rendering or in-app CV editor (Phase 2).

# Feature Requirements

| ID | Requirement / User Story | Priority |
|---|---|---|
| REQ-CAND-CV-01 | As a candidate, I **shall** be able to request a signed Cloudinary upload URL via `POST /api/v1/candidate/cvs/upload-url` before uploading my CV file. | High |
| REQ-CAND-CV-02 | As a candidate, I **shall** be able to register my uploaded CV file metadata in PostgreSQL via `POST /api/v1/candidate/cvs`. | High |
| REQ-CAND-CV-03 | As a candidate, the system **shall** restrict CV uploads to PDF (`application/pdf`), DOC (`application/msword`), and DOCX (`application/vnd.openxmlformats-officedocument.wordprocessingml.document`) formats with a max file size of 10 MB. | High |
| REQ-CAND-CV-04 | As a candidate, the system **shall** limit me to a maximum of 5 uploaded CV files per account. | Medium |
| REQ-CAND-CV-05 | As a candidate, my first uploaded CV **shall** automatically be marked as default (`is_default = true`). | Medium |
| REQ-CAND-CV-06 | As a candidate, I **shall** be able to retrieve my list of uploaded CVs via `GET /api/v1/candidate/cvs` to select a CV when applying for a job. | High |
| REQ-CAND-CV-07 | As an authorized user (candidate owner or assigned job recruiter), the system **shall** generate short-lived signed viewing URLs (TTL 15 min) via backend API to prevent public file exposure. | High |
| REQ-CAND-CV-08 | As a candidate, I **must** confirm an explicit data privacy consent checkbox ("I confirm this CV contains no sensitive national ID / bank details") prior to initiating a CV upload. | High |

# Business Logic

| ID | Rule / Constraint | Notes |
|---|---|---|
| BR-CAND-CV-01 | CV documents are stored in Cloudinary under a secured folder path (`candidates/{candidate_id}/cvs/`). Raw public CDN URLs must not be publicly listed or exposed. | Security rule |
| BR-CAND-CV-02 | Backend must validate MIME types (`application/pdf`, `application/msword`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document`) and enforce byte size <= 10,485,760 bytes (10 MB). | Input validation rule |
| BR-CAND-CV-03 | Candidate account is restricted to a hard limit of 5 active CVs in `candidate_cvs`. Attempting to upload a 6th CV returns HTTP 400 Bad Request. | Quota constraint |
| BR-CAND-CV-04 | If `candidate_cvs` contains 0 records for candidate, the newly registered CV must set `is_default = true`. Subsequent uploads set `is_default = false`. | Business rule |
| BR-CAND-CV-05 | Generated viewing URLs must expire after 15 minutes (900 seconds) and require valid JWT authentication. | Privacy constraint (Law 91/2025/QH15) |
| BR-CAND-CV-06 | Frontend CV upload modal must include prominent UI warning text and a mandatory un-checked consent box regarding sensitive ID masking. | Regulatory rule |

# Sub-tasks

- [ ] Validate business rules with team
- [ ] Implement Cloudinary SDK integration & signed upload URL generator service in Spring Boot
- [ ] Implement `POST /api/v1/candidate/cvs/upload-url` and `POST /api/v1/candidate/cvs` endpoints
- [ ] Implement `GET /api/v1/candidate/cvs` and `GET /api/v1/candidate/cvs/{id}/download-url` endpoints
- [ ] Add Flyway DB migration check for `candidate_cvs` schema
- [ ] Build UI upload modal with consent checkbox & Cloudinary upload flow in frontend
- [ ] Write backend unit tests for file type validation, quota enforcement, and presigned URL generation

# Changelogs

- 2026-10-07: Initial draft created by `@chiko`.
