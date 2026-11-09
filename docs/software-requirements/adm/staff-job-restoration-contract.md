# GW-107 — Staff Job Posting Restoration

| Field | Value |
|---|---|
| Specification ID | GW-SR-ADM-JOB-107 |
| Status | Draft; scoped implementation approved by the requesting maintainer in the GW-107 conversation |
| Owner | GuardWork thesis team |
| Human approver | Requesting project maintainer; display name awaiting confirmation |
| Last updated | 2026-11-03 |
| Scope | Backend UC-21 restoration and Company mutation freeze during Staff job suspension |

## Approval and amendment

The requesting maintainer answered `yes` to the proposed UC-21 backend contract and
to blocking all Company job mutations while a posting is Staff-suspended, while
retaining authorized read-only inspection. This amends the GW-93 rule that permitted
pending-revision withdrawal and assignment/responsibility management during
`SUSPENDED`. No frontend delivery, dependency, schema, or new notification channel
is approved here.

## Restore a hidden posting

- `PUT /api/staff/jobs/{jobId}/restore` accepts
  `{ "expectedJobVersion": 0, "reason": "Resolution details" }` and returns the
  existing `{status, message, data}` envelope. The receipt identifies job ID,
  `PUBLISHED` status, resulting version, Staff actor, restoration time and trimmed
  reason.
- Require a currently active persisted Platform `STAFF` actor at the service
  boundary. Company roles never grant this authority. Validate a nonnegative
  expected version and a reason of 1–1000 Unicode code points after stripping
  surrounding whitespace. Reject missing/invalid input with 400.
- Lock actor, Company, then posting. Refresh the locked posting. A stale job version
  returns 409 `40022 CONCURRENT_MODIFICATION`. A missing job returns 404
  `40478 JOB_NOT_FOUND`.
- Only `SUSPENDED` postings with an existing approved published revision and a
  future approved deadline may return to `PUBLISHED`. The Company must currently
  be verified and unbanned, and the posting's independent `staff_suspended` flag
  must be false. Other states or unmet eligibility return 409
  `40978 JOB_STATE_CONFLICT` and remain hidden. Staff attest that the hiding
  reason has been resolved through the mandatory restoration reason; there is no
  automated policy-resolution check.
- Atomically update posting status/version/time, append `JOB_RESTORED` to job
  history, and write a platform audit entry with actor, time, previous/new status,
  and reason. Failure of any mandatory step rolls back the mutation. Preserve
  approved content, revisions, Applications, assignments and earlier history.
- Public detail/search eligibility follows the existing `PUBLISHED` checks. No
  server content cache exists; clients must discard stale public job state when
  a hide or restoration succeeds. Restoration does not lift a Company ban or
  independent `staff_suspended` restriction.

## Company actions while hidden

For a posting whose status is `SUSPENDED`, reject every Company job mutation,
including draft creation/edit/submission, pending-revision withdrawal, lifecycle
changes, assignment/unassignment, and owner-responsibility changes. Reject even
no-op assignment/responsibility requests. The server enforces the rule after
locking the posting; UI controls alone are insufficient. Authorized Company
detail, revision history, and preview reads remain available. The separately
approved GW-100 Application-list contract continues to conceal Application data
for every Company Member while a posting is suspended.
Staff may still decide a pending revision under existing safeguards, but that
decision never restores public visibility. After Staff restoration, ordinary
Company permissions and job-state rules apply again.

The `staff_suspended` flag set by a Company ban is an independent restriction.
This contract does not redefine Company-ban enforcement or introduce a way to
clear that flag. Staff restoration refuses a posting while the flag is true.

## Verification and compatibility

Use the existing PostgreSQL test setup and controlled clock. Verify Staff and
Company authorization, missing/invalid/stale versions, exact deadline boundary,
Company state and independent suspension, successful public visibility and
preserved data, atomic job/platform audits and rollback, concurrent restore
attempts, and rejection of every Company mutation while `SUSPENDED`. Update
GW-93 tests that asserted the superseded withdrawal behavior. Run focused tests,
Maven compilation, the full test suite, and packaging. No migration is needed.
The new route is additive; private job status values are unchanged.
