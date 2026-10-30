# GW-89 — Simplified Job Posting Content

| Field | Value |
|---|---|
| Specification ID | GW-SR-JOB-SUBMISSION-SIMPLIFICATION |
| Project | GuardWork |
| Module | Job Publishing and Assignments |
| Version | 0.1.0 |
| Last Updated | 2026-10-30 |
| Owner | Thinh (inherited from the Job Publishing baseline) |
| Named human approver | Thinh (baseline approver; document approval pending) |
| Status | Draft |
| Issue | GW-89; local tracker under `.scratch/GW-89-job-submission-simplification/` |

## Problem Statement

Recruiters must split a Job Description into many mandatory fields before submitting
a Job Posting Revision for review. They must provide separate responsibilities,
required and preferred qualifications, experience expectations, benefits, and
application instructions, even when an existing description already contains that
information. Mandatory headcount and compensation disclosure add further friction.

The approved Job Publishing baseline explicitly requires these fields in JP78-012.
This specification proposes a focused replacement of that content requirement and
the corresponding content contract. The user accepted the decisions below during
the GW-89 design discussion; this document remains Draft for human review under
the repository's requirements policy.

## Solution

A Recruiter can paste a complete Job Description into one description field and
submit it with a title, employment type, work arrangement, location, and future
deadline. Headcount, compensation, and screening questions are optional. Platform
Staff use the same completeness rules when approving the revision.

The description can contain responsibilities, qualifications, experience, and
benefits as sections with headings and line breaks. Platform Staff assess whether
the description explains the actual job. The backend does not infer completeness
by detecting keywords or section headings.

## User Stories

1. As a Recruiter, I want to paste an existing Job Description into one field, so that I can avoid splitting the same content across separate inputs.
2. As a Recruiter, I want to submit with only six required content fields, so that posting a job takes less effort.
3. As a Recruiter, I want to save an incomplete draft, so that I can finish it later.
4. As a Recruiter, I want preview to identify missing required fields, so that I can correct the draft before submission.
5. As a Recruiter, I want optional headcount, so that I can post before the exact hiring target is known.
6. As a Recruiter, I want to omit compensation, so that I can submit when salary information is unavailable.
7. As a Recruiter, I want to explicitly withhold compensation when appropriate, so that I retain the existing disclosure choice.
8. As a Recruiter, I want supplied salary ranges checked, so that I do not publish invalid amounts.
9. As a Recruiter, I want optional screening questions, so that a basic Job Posting needs no questionnaire.
10. As a Recruiter, I want to distinguish employment type from work arrangement, so that Candidates understand both the engagement and where work happens.
11. As a Recruiter, I want to state an eligible area for remote work, so that Candidates understand geographic restrictions.
12. As a Recruiter, I want to state Worldwide eligibility for a remote job, so that I do not invent an office address.
13. As a Candidate, I want on-site and hybrid postings to state a work location, so that I can assess attendance requirements.
14. As a Candidate, I want a complete description with Vietnamese text and line breaks, so that I can understand the job.
15. As a Candidate, I want to apply through GuardWork, so that I do not need custom application instructions for each job.
16. As a Candidate, I want omitted salary and headcount to remain unspecified, so that the platform does not invent facts.
17. As Platform Staff, I want approval to accept the same content model as submission, so that optional fields do not block publication later.
18. As Platform Staff, I want to reject inadequate or contradictory descriptions through normal review, so that fewer mandatory inputs do not prevent moderation.
19. As an Organization Owner, I want verification and responsibility checks retained, so that simplified content does not bypass publishing controls.
20. As a Recruiter, I want version checks and explicit confirmation retained, so that submission still applies to the revision I reviewed.
21. As a Candidate, I want deadline expiry retained, so that unavailable jobs stop accepting applications under the existing lifecycle rules.

## Implementation Decisions

### Content contract

| ID | Decision |
|---|---|
| GW89-001 | Submission and approval require nonblank `title`, `description`, `employmentType`, `workArrangement`, `location`, and a strictly future `deadline`. |
| GW89-002 | Remove `responsibilities`, `requiredQualifications`, `preferredQualifications`, `experienceExpectations`, `benefits`, and `applicationInstructions` from accepted content. Their narrative information belongs in `description`. Existing unknown-field rejection also applies to these removed fields. |
| GW89-003 | Preserve current plain-text handling, trimming, Unicode code-point limits, line breaks, content size limits, and enum values. Description remains limited to 10,000 characters; title to 200 and location to 1,000. No HTML editor or HTML interpretation is introduced. |
| GW89-004 | `headcount` is optional, with no inferred value. When supplied it remains an integer from 1 to 10,000. |
| GW89-005 | `compensation` may be absent or null, meaning not provided. A supplied non-null object requires `disclosed` at submission and approval. An empty object is incomplete, rather than equivalent to omission. |
| GW89-006 | Disclosed compensation still requires positive minimum and maximum, currency VND/USD, and pay period HOUR/DAY/MONTH/YEAR. Retain precision 18, scale at most 2, minimum no greater than maximum, and whole-number VND amounts. Explicitly undisclosed compensation must have no range, currency, or pay period. |
| GW89-007 | Screening questions remain optional with all existing limits, types, ordering, stable keys, and conditional option rules. |
| GW89-008 | Incomplete drafts remain supported. Supplied draft values retain structural validation; completeness and cross-field eligibility apply at submission and approval. |

### Location and work arrangement

| ID | Decision |
|---|---|
| GW89-009 | Work Arrangement remains ONSITE, HYBRID, or REMOTE. Employment Type remains a separate concept with its existing enum values. |
| GW89-010 | For ONSITE and HYBRID jobs, location describes the work location. For REMOTE jobs, it describes the eligible work area, including country-level areas or an explicit Worldwide value. |
| GW89-011 | Keep location as required free text. Do not introduce geographic lookup, new location fields, or automatic region classification. Staff review contradictions between location and description. |

### Workflow, API, and data impact

- Update existing job content validation and eligibility within the current job
  subsystem. Reuse the same content rules for preview, submission, approval, and
  any existing publication eligibility checks.
- Keep the separate Job Posting lifecycle and Job Posting Revision moderation
  model. Preserve immutable submitted content and current audit behavior.
- Retain authorization, company verification and suspension checks, responsibility
  assignment, explicit submission confirmation, version checks, and expiry rules.
- Keep current endpoint routes, envelopes, status codes, and error conventions.
  Preview must stop reporting removed or optional fields as required.
- This is a breaking content contract change. Clients must stop sending removed
  fields and render optional values without assuming they exist. Candidate-facing
  content uses the complete description and supported structured fields.
- Applications remain within GuardWork. Staff still reject descriptions that
  require applying outside the platform.
- Existing development Job Postings may be discarded; backward compatibility or
  migration of their narrative fields is not required. This is not an instruction
  to delete records automatically. Any reset must be explicit and scoped to
  disposable development data, including related revisions and references.
- No new dependency, database schema, security boundary, personal-data collection,
  or infrastructure is proposed. Existing JSON content storage supports the model;
  implementation must check for constraints that depend on the removed fields.
- Before implementation is accepted, synchronize the baseline requirement
  JP78-012, its implementation contract, backend documentation, and HTTP examples.
  JP78-011 completeness refers to the revised required content once approved.

## Testing Decisions

Use the existing HTTP integration boundary, covering actual requests, responses,
and persisted workflow outcomes. No new testing seam or infrastructure is proposed.
The user accepted implementation after the specification and ticket breakdown;
the implementation uses this existing HTTP integration boundary.

Prior art exists in the job draft, submission, publication, and lifecycle HTTP
integration suites. Extend those suites rather than test private helper methods.

1. Save a draft with the six required fields and no optional fields; preview is
   eligible, submission succeeds, staff approval succeeds, and public detail
   contains the supported content.
2. Omit each required field in turn, and use blank required text; preview reports
   the relevant field and submission rejects incomplete content. Approval must
   enforce the same completeness rules.
3. Verify incomplete drafts can still be saved and preview has no submission side
   effect.
4. Verify each removed field is rejected as unknown on content writes.
5. Verify optional headcount accepts omission/null and valid integers, but rejects
   invalid supplied values.
6. Verify omitted/null compensation succeeds; a supplied empty object fails
   completeness; explicit undisclosed compensation succeeds without salary data.
   Retain disclosed-range and prohibited-undisclosed-field cases.
7. Verify valid optional screening questions and existing invalid question cases.
8. Verify REMOTE with Vietnam or Worldwide is accepted, along with valid ONSITE
   and HYBRID locations. Retain blank location rejection without inventing a
   geographic parser.
9. Verify Vietnamese text, line breaks, and current field length/type/enum limits.
10. Retain exact-deadline tests, including expiry between submission and approval.
11. Retain company eligibility, responsibility, authorization, confirmation,
    concurrency, immutable revision, and public data exposure regression coverage.

## Out of Scope

- Frontend implementation, rich-text editing, automated description scoring, or
  keyword-based moderation.
- New location architecture, country pickers, geocoding, or time-zone matching.
- New salary formats such as minimum-only, maximum-only, or negotiable salary.
- Optional deadlines, automatic deadline defaults, or indefinite publication.
- Application collection changes, automatic ranking, or rejection of Candidates.
- Production data deletion, historical content conversion, broad refactoring, or
  changes to authentication, notification, and persistence architecture.

## Further Notes

The user confirmed the content decisions and authorized continuing on the current
GW-89 branch, stating that it is up to date with dev. Implementation was subsequently authorized on that branch. Existing jobs must
not be deleted automatically.

The project tracker uses local Markdown files. The tracker spec links here;
numbered tickets and their map live under `.scratch/GW-89-job-submission-simplification/`.
No external issue publication is needed.

Industry references informed the discussion; they are not GuardWork requirements:

- [Google JobPosting guidance](https://developers.google.com/search/docs/appearance/structured-data/job-posting): distinguishes complete description content from separate structured properties.
- [LinkedIn job posting schema](https://learn.microsoft.com/en-us/linkedin/talent/job-postings/api/job-posting-api-schema): distinguishes remote geographic areas from on-site and hybrid locations.
- [Indeed location policy](https://www.indeed.com/help/employers/articles/appropriate-use-of-location-on-indeed): describes geographic restrictions for fully remote work.
