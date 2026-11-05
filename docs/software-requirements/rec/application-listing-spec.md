# Recruiter Application Listing — Software Requirements Specification

| Field | Value |
|---|---|
| Specification ID | GW-SR-RECRUITER-APPLICATION-LISTING |
| Project | GuardWork |
| Module | Application review |
| Version | 1.0.0 |
| Date | 2026-11-01 |
| Last Updated | 2026-11-05 |
| Owner | Thinh |
| Approver | Thinh |
| Status | Draft metadata retained under repository policy; explicit human approval recorded below |

## Problem Statement

Company Owners and Recruiters can manage Job Postings, but cannot list the Applications submitted to those Job Postings. The existing Candidate-facing listing does not provide the Company review workflow. Company Members need a small, private list that respects current Company Membership, Job Assignments, and suspension rules.

## Solution

Provide a paginated backend listing for one selected Job Posting. Authorized Company Members can identify submitted Candidates, search their submitted names with or without Vietnamese accents, and filter by the current Application status. Return only the summary information needed to select an Application for a future detail workflow. Block listing for suspended Job Postings, including for the Company Owner.

## Further Notes

The user accepted interview Q1–Q3 and Q4–Q8 on 2026-11-01. These decisions define a backend-only Application listing for one selected Job Posting. Application details, CV access, stage changes, and a Company-wide Application inbox are separate delivery slices.

This focused specification follows the approved Job Publishing MVP's Owner/Recruiter authorization boundary. It does not approve the broader Application Pipeline draft's Admin access or replace the current `SUBMITTED` status with its proposed `APPLIED` status.

After the suspended-job restriction was recorded, Thinh explicitly answered “Yes. $to-spec” to the request to confirm shared understanding and approve this specification for implementation. This records human approval on 2026-11-01; the agent has not changed the metadata status to Approved. Thinh subsequently answered “Yes” to the proposed HTTP integration-test boundary with Spring Security and PostgreSQL, completing the to-spec confirmation.

## User Stories

1. As a Company Owner, I want to list Applications for a selected Company Job Posting, so that I can review recruitment activity.
2. As a Recruiter, I want to list Applications for a Job Posting assigned to my current Active Membership, so that I can review work assigned to me.
3. As a Candidate, I want Applications hidden from other Companies, so that my submitted information remains private.
4. As a Candidate, I want Applications hidden from unassigned Recruiters, so that access follows recruitment responsibility.
5. As a Candidate, I want former Company Members to lose access, so that their old membership does not grant continuing access.
6. As a Candidate, I want removed Job Assignments to stop access, so that former assignments do not grant continuing access.
7. As a Candidate, I want a new membership period to require a valid current assignment, so that old assignments do not revive access.
8. As a Candidate, I want Company Admin excluded from this workflow, so that access follows the approved Company-role boundary.
9. As a Company Member, I want submitted Candidate names in the list, so that I can identify Applications.
10. As a Company Member, I want names from the Application Snapshot, so that later Candidate profile changes do not rewrite submitted evidence.
11. As a Company Member, I want the current status and submission time, so that I can understand each Application's position and age.
12. As a Company Member, I want an Application identifier, so that a client can identify a selected Application.
13. As a Company Member, I want partial name search, so that I can find an Application without entering the whole name.
14. As a Company Member, I want case-insensitive and accent-insensitive name search, so that `Nguyen` can find `Nguyễn`.
15. As a Company Member, I want original submitted names preserved in results, so that search normalization does not alter names.
16. As a Company Member, I want exact Application-status filtering, so that I can narrow the list to the supported status.
17. As a Company Member, I want pagination with accurate filtered totals, so that I can navigate results.
18. As a Company Member, I want newest submissions first with deterministic ties, so that equal timestamps have a predictable order.
19. As a Company Member, I want an empty successful result when nothing matches, so that I can distinguish no results from a failure.
20. As a Company Member, I want invalid search, status, and pagination values rejected, so that mistakes do not silently produce misleading results.
21. As a Company Member, I want listing access for paused, closed, and expired Job Postings, so that I can review existing submissions after intake stops.
22. As a Candidate, I want a suspended Job Posting to block Application listing even for the Company Owner, so that suspension protects its Application data.
23. As a Candidate, I want suspended or unverified Companies blocked, so that Company eligibility controls access to Applications.
24. As a Candidate, I want inaccessible Job Postings indistinguishable from absent ones, so that callers cannot discover private resources or counts.
25. As a Candidate, I want listing to omit contact details, cover letters, CV access, and profile or storage identifiers, so that the list exposes only its required summary.
26. As a Candidate, I want names and search text omitted from logs, so that routine logging does not retain that personal information.
27. As a Company Member, I want listing to send no notifications or change recruitment state, so that browsing has no workflow side effects.

## Functional requirements

| ID | Requirement |
|---|---|
| RAL-001 | A Company Owner may list Applications for any Job Posting in their Company. A Recruiter may list only Applications for Job Postings assigned to their current active Company Membership period. Company Admin is excluded. |
| RAL-002 | Every request shall recheck live actor, Company Membership, Company, and Job Assignment authorization. Inaccessible and absent Job Postings shall return the same 404 response. |
| RAL-003 | Each row shall contain only Application ID, submitted Candidate name from the immutable Application Snapshot, current Application status, and submission time. It shall not expose contact information, cover letters, CV access, Candidate profile identifiers, or storage identifiers. |
| RAL-004 | The list shall support case-insensitive partial matching on submitted Candidate name and exact Application-status filtering. This slice shall preserve `SUBMITTED` and introduce no new stages. |
| RAL-005 | Results shall use the existing `ApiResponse<PageResponse<...>>` convention, zero-based pages, default size 20, maximum size 100, and fixed ordering by submission time descending then Application ID descending. |
| RAL-006 | Authorized listing shall remain available for paused, closed, and expired Job Postings while the Company is verified and unsuspended. Loss of Company Membership or Job Assignment shall revoke access. |
| RAL-007 | A suspended Job Posting shall block Application listing for every Company Member, including the Company Owner and assigned Recruiters. Return the same non-enumerating 404 used for an inaccessible or absent Job Posting, without rows or totals. |

## Existing data and compatibility

Read existing Applications and immutable Application Snapshots. Existing Candidate submission and listing endpoints remain compatible. No dependency or database schema change has been approved or proposed for this slice.

The broader Application Pipeline and Interview/Offer specifications remain Draft. This slice does not establish a retention duration, permit reopening, or approve future post-process access rules.

## Implementation Decisions

The user accepted Q9, Q12, and Q13 on 2026-11-01 and requested accent-insensitive search in Q10 if practical. Existing job search already uses the installed PostgreSQL `unaccent` extension; reuse it without adding dependencies or changing the schema.

- Route: `GET /api/companies/me/jobs/{jobId}/applications`.
- Optional parameters: `search`, `status`, `page` (default 0), `size` (default 20; range 1–100). Reject negative pages and pagination offset overflow.
- Row fields: `id` (Application UUID), `candidateName` (snapshot full name), `status`, `appliedAt` (instant).
- Search: trim surrounding whitespace; blank means no filter; maximum 150 characters; case-insensitive and accent-insensitive partial name matching. `Nguyen` shall match `Nguyễn`. Treat `%` and `_` as literal characters. Preserve original submitted names in responses.
- Status: exact `SUBMITTED` or omitted; other values return 400. No new stage is introduced.
- No matches or a page beyond the result set returns 200 with empty content and accurate filtered pagination totals. Invalid query values return 400 using the existing scoped error convention.
- Listing sends no notifications and introduces no business audit events. Existing request/security logging applies; do not log Candidate names or search text.
- Listing covers existing retained submissions. This slice establishes no retention duration or reopening rights. Later approved retention/deletion policies must constrain listing.
- Extend the existing Application module with a thin Company-scoped controller, service authorization/query orchestration, repository-level filtered pagination, and a minimal response projection. Reuse existing Company access and current Job Assignment rules rather than creating an authorization framework.
- Filter and paginate in PostgreSQL, including the Company and Job Posting scope. Use existing Application and Application Snapshot data; avoid a per-row snapshot fetch. Preserve existing Candidate APIs.
- Use live active-User and verified account-email checks required for private Company operations. Do not trust cached token roles or client-supplied Company authority.
- Preserve current scoped account and Company errors; conceal absent, other-Company, unassigned, and suspended Job Postings with 404. Scope resource access before returning rows or filtered counts.
- Do not add dependencies, migrations, database extensions, frontend components, or a new shared response/error architecture.

## Job suspension boundary

On 2026-11-01, the user rejected the proposed continued Application-list access for suspended Job Postings, explicitly including the Company Owner in the restriction. Q10 accent-insensitive search was also explicitly accepted.

The approved GW-93 Staff job contract preserves authorized reads of Job Posting content and history after suspension. It does not define Application listing. This focused requirement blocks access to Application data while preserving the approved Job Posting read behavior; it does not change GW-93. Company suspension or loss of Company verification also blocks Application listing. Paused, closed, and expired Job Postings retain listing access under RAL-006. Restoration of suspended Job Postings remains outside this slice.

## Testing Decisions

Confirmed main test boundary: the existing HTTP integration-test setup with Spring Security and the existing isolated PostgreSQL test environment. Exercise real endpoint behavior and persisted authorization/query rules rather than mocking repository answers or asserting internal method calls. Existing Application submission HTTP tests and Company Job Posting tests provide prior art. Reuse existing test infrastructure and fake external CV storage where necessary; do not introduce a new framework.

Prove Company isolation; Owner and current-assignment Recruiter access; Admin exclusion; membership and assignment revocation; Company verification/suspension gates; suspended-job denial for both Owner and assigned Recruiter without rows or totals; inaccessible-resource concealment; immutable submitted names; minimal response fields; accent-insensitive search/filter behavior; stable pagination; and paused/closed/expired job access. Run the backend's documented checks after implementation.

Also cover unauthenticated and inactive actors, unverified account email, old membership-period assignments, literal search wildcards, whitespace/length limits, exact status validation, tied timestamps, empty and out-of-range pages, accurate filtered totals, and unchanged Candidate listing/submission behavior. Test the response contract and observable side effects; add lower-level tests only if an important behavior cannot be proved adequately at this boundary.

## Out of Scope (original per-job slice)

Frontend implementation; a Company-wide Application inbox; Application details; contact information; CV viewing/download; cover letters; stage changes; rejection; withdrawal; internal notes; reopening; reapplication; interviews; Offers; Hiring Confirmation; new Application statuses; Admin enablement; Job Posting restoration; configurable sorting; new business audit events; notifications; retention durations or deletion workflows; dependencies; schema changes; commits; pushes; and PR publication.

## Company-wide inbox extension (2026-11-05)

The user explicitly accepted the Company-wide API proposal (including Owner access)
and requested implementation on the current branch on 2026-11-05. This records human
approval for the additive extension; the existing specification status is unchanged.
Owner and named approver remain Thinh. The original per-job slice stays compatible.

- RAL-INBOX-001: `GET /api/companies/me/applications` shall return Applications across
  all eligible Company jobs for the Owner, or only currently assigned jobs for a
  Recruiter's active membership period. Admin remains excluded. Apply existing live
  actor, email, membership and Company eligibility checks on every request.
- RAL-INBOX-002: Exclude suspended jobs from rows and totals. Permit paused, closed
  and expired jobs. Apply authorization and all filters in PostgreSQL before paging.
- RAL-INBOX-003: Accept the existing search, status and pagination contracts plus an
  optional positive `jobId`. A missing or inaccessible selected job returns the same
  concealed 404 as the per-job route. Without a selected job, no matches returns 200.
- RAL-INBOX-004: Return the existing summary fields plus `jobId` and snapshot
  `jobTitle`, ordered by `appliedAt DESC, id DESC`, in the existing page envelope.
  Contact details, CV data and profile/storage identifiers remain excluded. Send
  `Cache-Control: no-store`; reads produce no business audit or notification.
- RAL-INBOX-005: Preserve the per-job endpoint and existing clients. No schema,
  dependency, configuration or project-wide contract change is part of this slice.
  Existing indexes are reused; exact counts, substring search and deep offsets
  remain performance considerations requiring representative query-plan checks.

Validation: PostgreSQL-backed HTTP tests cover multi-job isolation, current assignment
and membership revocation, Owner access, filtering, deterministic pagination, accurate
totals, privacy, parameter errors and existing Company eligibility checks.
