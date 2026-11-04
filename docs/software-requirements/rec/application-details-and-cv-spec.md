# Application Details and CV Viewing — Software Requirements Specification

| Field | Value |
|---|---|
| Specification ID | GW-SR-APPLICATION-DETAILS |
| Project | GuardWork |
| Owner | Thinh |
| Approver | Thinh |
| Status | Draft metadata retained under repository policy; explicit human approval recorded below |
| Version | 0.1.0 |
| Date | 2026-11-03 |
| Last Updated | 2026-11-03 |

## Scope and approval record

On 2026-11-03, Thinh answered “yes” to Q16, confirming shared understanding and approving implementation of this document, including its concrete HTTP contracts and validation. Thinh subsequently approved the five-ticket breakdown. The agent has not set lifecycle metadata to Approved.

Backend application details and authenticated viewing of the existing submitted CV copy. On 2026-11-03, Thinh approved snapshot-only evidence, listing-equivalent authorization, authenticated CV streaming with sensitive-access audit, and the terminal-access restriction below. No frontend, live profile enrichment, new education/experience snapshot, standalone download action, dependency, or deletion schedule is included.

This extends [Application Listing](application-listing-spec.md) and uses existing Application Snapshots. The [broader pipeline](../../feat/recruiters/application-pipeline-spec.md) remains a draft; this focused slice excludes Admin access consistently with the implemented listing contract.

## Functional requirements

| ID | Requirement |
|---|---|
| AD-001 | Company details shall show submitted name, email, phone, cover letter, job title, company name, current status and version, and safe CV metadata. Evidence shall come from the immutable Application Snapshot. |
| AD-002 | Only the current Company Owner or a current-period assigned Recruiter may access Company application resources. Reuse listing checks for live account, verified email, membership, Company eligibility, and job assignment. Admin remains excluded. |
| AD-003 | Absent, foreign, unassigned, or suspended jobs/applications shall use non-enumerating 404 responses. Existing scoped account/membership errors remain unchanged. Paused, closed, and expired jobs remain reviewable. |
| AD-004 | Details shall not change Application Status. Viewing shall not create stage history or business notifications. |
| AD-005 | CV viewing shall stream the application-specific CV copy through an authenticated backend endpoint, rechecking current authorization for each request. No storage key, provider credential, or reusable storage URL shall appear in JSON responses. |
| AD-006 | CV viewing shall record a sensitive-access audit event without copying Candidate contact data, CV content, or credentials into logs. Private details and CV responses shall use no-store caching. |
| AD-007 | After REJECTED or WITHDRAWN, Company readers shall receive only minimal listing-equivalent identity and workflow history. Contact details, cover letter, and CV access shall be unavailable. Internal reasons remain visible only to authorized Company readers. |
| AD-008 | Candidate-owned details shall expose current status, version, transition times and explicitly Candidate-facing rejection feedback. Internal reasons, internal explanations and recruiter identities shall never be exposed to Candidates. |
| AD-009 | Company details shall include authorized actor attribution and workflow history when the review slice is delivered. History shall not expose personal contact data. |

## Human-approved HTTP contract

- `GET /api/companies/me/jobs/{jobId}/applications/{applicationId}`: Company detail projection, with terminal redaction.
- `GET /api/companies/me/jobs/{jobId}/applications/{applicationId}/cv`: authenticated inline PDF response; DOC/DOCX snapshots return 406; terminal access denied with 404.
- `GET /api/candidate/applications/{applicationId}`: Candidate-owned detail projection, inaccessible applications concealed with 404.
- JSON responses reuse existing envelopes. CV success is binary with safe inline filename and media type. Storage failure shall return a controlled error without leaking upstream details.
- No claim is made that browser viewing prevents saving or copying the CV.

## Privacy and retention boundary

The terminal restriction limits Company access; it does not erase stored data or establish a lawful retention duration. Reopening, deletion, correction windows, and post-process personal-data access remain subject to a separately approved retention policy. No legal compliance determination is made here.

## Verification

Verify snapshot stability; Company and Candidate isolation; current assignment/membership revocation; Admin exclusion; suspended-job concealment; paused/closed/expired access; terminal redaction; authenticated CV streaming and audit; no storage leakage; and Candidate-safe history. Use existing PostgreSQL HTTP integration infrastructure and documented backend checks.
