# Staff Sprint 4 — Backend Readiness and Remaining Work

Date: 2026-11-01
Status: Planning notes; not an approved implementation contract.
Scope: Company verification, Job Posting management, and account management.

## Baseline

The local backend already implements shared registration, login, refresh, logout,
and explicitly requested six-digit email verification. OTPs expire after ten
minutes, allow five failed guesses, and enforce a sixty-second resend cooldown.
Registration does not automatically send email. Delivery configuration and actual
mailbox receipt require deployment verification.

Staff account management supports user/Company search and detail, permanent and
temporary bans, unban, expiry, session invalidation, and atomic audit recording.
Company unban does not restore suspended Job Postings.

Company verification supports complete multipart dossiers, private evidence,
pending review, approval/rejection, retained history, Owner correction/resubmission,
and decision-delivery intents for retained attempts. Legacy dossiers without
retained attempts have a documented notification gap.

Job management supports drafts, submission, Staff revision review and decisions,
publication, Company lifecycle operations, Staff hiding, and posting-event history.
Staff navigation currently uses submitted revisions rather than a complete
job-oriented management list/detail API.

These findings come from source and existing test inspection. They do not certify
that tests pass or that deployed end-to-end journeys work.

## 1. Company verification — blocking journey

Prioritize the existing flow before adding new functionality:

1. Register and sign in as a public User.
2. Request and confirm the account-email OTP.
3. Submit a complete Company dossier with its license file.
4. Confirm Company ownership and pending verification without recruiting authority
   that depends on verification.
5. Sign in as Staff and inspect the dossier, private evidence, and history.
6. Approve; verify the intended downstream recruiting operations become available.
7. Separately test rejection, Owner feedback, correction, resubmission, and approval.
8. Verify stale decisions, unauthorized downloads, failed audit rollback, and
   decision-email failure/retry without repeating the business decision.

### Automatic MST lookup experiment

No masothue.com integration was found. Before implementation, inspect the site's
current access conditions and available integration options. Agree on tax-code
normalization, returned fields, source timestamps, matching rules, timeouts,
failure handling, and any persisted lookup evidence. A provider failure must be
distinguishable from an invalid tax code. Define whether unavailable lookup allows
manual review or blocks submission; do not choose this policy implicitly.

Treat lookup results as review assistance unless an approved requirement changes
the Staff decision boundary. Never automatically approve a Company solely from a
lookup match. Any dependencies, schema changes, or external integration contract
need the repository's explicit approvals.

## 2. Job Posting management — blocking journey

Verify Company draft creation, assignment, submission, Staff approval/rejection,
public discovery/detail, and Staff hiding/history. Verify suspended postings are
unavailable through public search/detail and cannot be restored through Company
editing, resume, or a retained revision approval.

Decide whether the intended Staff screen needs a dedicated job-oriented list and
detail contract. The revision queue can contain multiple rows per job and is not
a complete publication-state inventory. Search/status filters and returned fields
must follow a focused approved contract if added.

UC-21 restoration/republishing remains unimplemented and outside GW-93 approval.
Before implementation, define eligibility, interaction with Company bans,
verification/deadlines, independent suspension representations, optimistic
concurrency, audit, and public visibility recovery.

## 3. Account management

Reuse GW-92 APIs rather than rebuilding account management. Verify search/detail,
self-ban refusal, temporary/permanent bans, unban, expiry, old access/refresh token
rejection, and all active Company member roles. Confirm that personal access and
Company restrictions remain distinct, and unban never verifies a Company or
republishes suspended postings. Verify rollback when required audit or session
invalidation fails.

## Current-task boundary: user reports

The initial inspection found no backend user-report workflow. GW-99 now adds
authenticated report submission, Staff list/detail/filtering and private evidence
under the maintainer-approved [focused contract](../../software-requirements/adm/user-report-intake-contract.md).
UC-11–12 cover
Staff list/detail reads, status/target filters, permitted reporter information,
evidence availability, and existing outcomes. Reads must not start review, resolve
reports, or apply sanctions. Report adjudication is separate work. Automatic MST lookup and UC-21 should
remain upcoming work unless explicitly brought into scope.

## Verification and completion

Use the existing Maven, MockMvc, and isolated PostgreSQL infrastructure. Agree on
public test boundaries before TDD. Run focused tests as changes land and the full
backend suite once at completion; run packaging for migration/configuration/build
changes. Browser E2E, actual inbox delivery, and frontend rendering require their
own available environment and must not be inferred from backend tests.

Record actual commands/results, omitted checks, migration/deployment requirements,
client contracts, and remaining approval decisions in the implementation handoff.

## References

- [Staff specification](spec.md) and [use cases](usecase.md)
- [Company setup](../../backend/company-setup.md)
- [Company correction](../../backend/company-verification-correction.md)
- [Staff account integration](../../backend/staff-account-management.md)
- [Staff job integration](../../backend/staff-job-management-frontend-integration.md)
- [Registration and OTP](../../backend/unified-registration.md)
- [GW-92 contract](../../software-requirements/adm/account-management-spec.md)
- [GW-93 contract](../../software-requirements/adm/staff-job-management-contract.md)
