# Application Details and CV Viewing — Software Requirements Specification

| Field | Value |
|---|---|
| Specification ID | GW-SR-APPLICATION-DETAILS |
| Project | GuardWork |
| Owner | Thinh |
| Approver | Thinh |
| Status | Draft metadata retained under repository policy; explicit human approval recorded below; amended 2026-11-18 |
| Version | 0.2.0 |
| Date | 2026-11-03 |
| Last Updated | 2026-11-18 |

## Scope and approval record

On 2026-11-03, Thinh answered “yes” to Q16, confirming shared understanding and approving implementation of this document, including its concrete HTTP contracts and validation. Thinh subsequently approved the five-ticket breakdown. The agent has not set lifecycle metadata to Approved.

Backend application details and authenticated viewing of the submitted CV. On 2026-11-03, Thinh approved snapshot-only evidence, listing-equivalent authorization, authenticated CV streaming with sensitive-access audit, and the terminal-access restriction below. The 2026-11-18 amendment replaces the per-Application copy with a shared access grant and adds a Company download-URL endpoint, inactivity and rejected-copy purge. No frontend, live profile enrichment, new education/experience snapshot, dependency or legal retention determination is included.

This extends [Application Listing](application-listing-spec.md) and uses existing Application Snapshots. The [broader pipeline](../../feat/recruiters/application-pipeline-spec.md) remains a draft; this focused slice excludes Admin access consistently with the implemented listing contract.

## Functional requirements

| ID | Requirement |
|---|---|
| AD-001 | Company details shall show submitted name, email, phone, cover letter, job title, company name, current status and version, and safe CV metadata. Evidence shall come from the immutable Application Snapshot. |
| AD-002 | Only the current Company Owner or a current-period assigned Recruiter may access Company application resources. Reuse listing checks for live account, verified email, membership, Company eligibility, and job assignment. Admin remains excluded. |
| AD-003 | Absent, foreign, unassigned, or suspended jobs/applications shall use non-enumerating 404 responses. Existing scoped account/membership errors remain unchanged. Paused, closed, and expired jobs remain reviewable. |
| AD-004 | Details shall not change Application Status. Viewing shall not create stage history or business notifications. |
| AD-005 | CV viewing shall stream the authorized shared library CV object (or the historical `LEGACY_COPY` object) through an authenticated backend endpoint, rechecking current authorization for each request. No storage key, provider credential, or reusable storage URL shall appear in JSON responses. |
| AD-006 | CV viewing shall record a sensitive-access audit event without copying Candidate contact data, CV content, or credentials into logs. Private details and CV responses shall use no-store caching. |
| AD-007 | After REJECTED or WITHDRAWN, Company readers shall receive only minimal listing-equivalent identity and workflow history. Contact details, cover letter, and CV access shall be unavailable. After INACTIVE, detail visibility remains but Company CV access is unavailable. Internal reasons remain visible only to authorized Company readers. |
| AD-008 | Candidate-owned details shall expose current status, version, transition times and explicitly Candidate-facing rejection feedback. Internal reasons, internal explanations and recruiter identities shall never be exposed to Candidates. |
| AD-009 | Company details shall include authorized actor attribution and workflow history when the review slice is delivered. History shall not expose personal contact data. |

## Human-approved HTTP contract

- `GET /api/companies/me/jobs/{jobId}/applications/{applicationId}`: Company detail projection, with terminal redaction and `canViewCv` / `reviewWindowEndsAt` / `inactivityDeadline` access metadata.
- `GET /api/companies/me/jobs/{jobId}/applications/{applicationId}/cv`: authenticated inline PDF response; DOC/DOCX snapshots return 406; terminal and `INACTIVE` access denied with 404.
- `GET /api/companies/me/jobs/{jobId}/applications/{applicationId}/cv/download-url`: five-minute signed URL for PDF, DOC, or DOCX under the same grant and access checks.
- `GET /api/candidate/applications/{applicationId}`: Candidate-owned detail projection, inaccessible applications concealed with 404.
- JSON responses reuse existing envelopes. CV success is binary with safe inline filename and media type. Storage failure shall return a controlled error without leaking upstream details.
- No claim is made that browser viewing prevents saving or copying the CV.

## Privacy and retention boundary

The terminal restriction limits Company access; rejected legacy-copy bytes are deleted under AD-015,
but this specification does not establish a general lawful retention duration. Reopening, broader
deletion, correction windows and post-process personal-data access remain subject to a separately
approved retention policy. No legal compliance determination is made here.

## Verification

Verify snapshot stability; Company and Candidate isolation; current assignment/membership revocation; Admin exclusion; suspended-job concealment; paused/closed/expired access; terminal redaction; authenticated CV streaming, download URL and audit; newly assigned Recruiter access; `INACTIVE` concealment and reactivation epochs; rejected-copy purge; no storage leakage; and Candidate-safe history. Use existing PostgreSQL HTTP integration infrastructure and documented backend checks.

## Amendment 2026-11-18: grant-based CV access, inactivity, and rejected-copy purge

Status: approved by the requesting human user on 2026-11-18. GW-SR-CV-02 is the normative owner of
the CV access lifecycle; this section records how the approved amendment changes AD-005, AD-007 and
the HTTP contract above.

| ID | Amended requirement |
|---|---|
| AD-010 | Company CV access **shall** be authorized by a live `candidate_cv_access_grants` row for the requested Application and the authenticated Company. The snapshot `cv_storage_key` **shall not** authorize access. New Applications share the Candidate library object; pre-existing Applications keep their `LEGACY_COPY` object. |
| AD-011 | The Company **shall** also have `GET /api/companies/me/jobs/{jobId}/applications/{applicationId}/cv/download-url`, returning a five-minute signed provider URL for PDF, DOC, or DOCX. The inline route remains PDF-only. Neither route exposes a storage key or reusable URL. |
| AD-012 | `REJECTED`, `WITHDRAWN`, and `INACTIVE` Applications **shall** conceal Company CV access with the same non-enumerating not-found response. Company contact/cover-letter visibility remains for `INACTIVE`; terminal redaction is unchanged. |
| AD-013 | If the Candidate deletes the shared library CV, the Company keeps a bounded review window (default 30 days) only while the Application is in an active review stage. `HIRED` ends shared-CV access immediately. The Candidate loses their own access immediately. |
| AD-014 | `INACTIVE` is reversible through `UNDER_REVIEW` by an eligible Owner or currently assigned Recruiter. Reactivation starts a new CV grant epoch only while the shared library CV still exists. |
| AD-015 | On `REJECTED`, the application-specific `LEGACY_COPY` bytes **shall** be deleted by a bounded, retryable worker while snapshot metadata is retained. `SHARED` library objects **shall not** be deleted by this path. Already-rejected copies are backfilled. `WITHDRAWN` copies are retained. |
| AD-016 | Company detail **shall** expose `canViewCv`, `reviewWindowEndsAt`, and `inactivityDeadline`. `canViewCv` reflects the same state as the CV endpoints; `reviewWindowEndsAt` is the live shared grant expiry (null when open-ended or unavailable); `inactivityDeadline` is `statusChangedAt` plus the configured inactivity TTL for active stages, and null otherwise. Candidate detail exposes `canViewCv` for the Candidate’s own submitted CV; Company-only deadlines are null for the Candidate. |

The earlier statement that no standalone download action exists is superseded for Company readers
by AD-011. The earlier statement that no automatic deletion exists is superseded by AD-015; the
legal retention-agreement exception remains deferred. Tests shall cover newly assigned Recruiters,
`INACTIVE` concealment, reactivation epochs, HIRED deletion, and rejected-copy purge.
