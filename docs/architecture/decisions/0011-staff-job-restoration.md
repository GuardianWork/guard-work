# Staff Job Posting restoration and mutation freeze

On 2026-11-03 the requesting maintainer approved the focused
[GW-107 contract](../../software-requirements/adm/staff-job-restoration-contract.md)
for UC-21 and the Company mutation freeze during Staff job suspension.

Restore `SUSPENDED → PUBLISHED` only through a Staff endpoint with current job
version, mandatory resolution reason, current approved content, future deadline,
verified unbanned Company, and no independent `staff_suspended` restriction.
Reuse the actor → Company → Job Posting lock order and existing job/platform audit
stores. The mandatory reason records Staff's resolution assessment; the backend
does not infer policy resolution from job content. No schema or dependency changes.

Company job writes, including withdrawal and assignment/responsibility changes,
are refused during individual Staff suspension. Authorized job detail, preview,
and revision reads remain. The separately approved GW-100 Application-list privacy
rule continues to conceal Application data during suspension. Staff review of a
pending revision may still complete without restoring visibility.

The Company-ban `staff_suspended` flag remains a separate restriction and is not
cleared by this restore operation. Public reads continue to apply all existing
eligibility checks. No server content cache exists in this backend; any separately
owned frontend index or cache must invalidate stale visibility on hide/restore.
