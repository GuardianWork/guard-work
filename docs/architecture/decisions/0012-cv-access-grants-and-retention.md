# Candidate CV access grants and retention

On 2026-11-17 the requesting maintainer approved the requirement amendment in GW-SR-APP-01
(section 13) and GW-SR-CV-02: attaching a CV no longer copies the object into an
application-specific key. Submission shares the one library object and records a
`candidate_cv_access_grants` row for the receiving Company in the same transaction. Only a live
grant (not revoked, not expired) authorizes a Company CV read; the snapshot `cv_storage_key` is
descriptive only. This removes duplicated bytes per application, closes the gap where a copied
object remained readable from a stale snapshot, and gives the product owner an explicit,
auditable permission with a bounded end.

A Candidate who deletes the library CV immediately loses access to that CV. The receiving Company
keeps access for a bounded review window after deletion (default 30 days,
`candidate.cv.access-grant-ttl`); this is intentional, because an employer reviewing an application
must not be cut off by the Candidate mid-review. Once every grant ends, a scheduled worker purges
the object (`candidate.cv.retention.*`), while access is denied in the meantime at read time.
Tombstone rows keep grants' foreign keys valid and make purge retries idempotent.

The migration (`V2026.11.17.108`) adds `deleted_at`/`storage_deleted_at` to `candidate_cvs`, the
grant table with identity-immutable triggers, `application_snapshots.cv_storage_mode` (default
`LEGACY_COPY`), and swaps the one-default index to exclude removed CVs. Existing applications keep
their copied objects with no grant backfill; there is no path that silently switches a `SHARED`
snapshot to its storage key. Terminal `REJECTED` and `WITHDRAWN` statuses revoke grants immediately.

Trade-offs: pre-existing Cloudinary objects uploaded with public delivery remain directly reachable
and are not rewritten (new uploads require `access_mode=authenticated` and registration rejects
anything else). Grant expiry is enforced by query predicates, not a scheduled sweep, so the grant
row persists after expiry. Purge is eventual and independent of access, which can outlive the review
window only by worker latency—never the permission. Deleting a library CV clears its default flag.
Grants prevent hard-deleting their CV/profile rows until revocation/expiry plus a future approved
account-deletion design; the tombstone is the supported lifecycle.

## Amendment 2026-11-18: inactivity, epochs, and rejection purge

The requesting human user approved a second amendment. An active Application with no status change
for 30 days (default) becomes reversible `INACTIVE`; its current grant epoch is revoked. Reactivation
must pass through `UNDER_REVIEW` and starts a new epoch only when the shared library CV still exists.
The partial unique index `(application_id) WHERE revoked_at IS NULL` preserves one live epoch per
Application while keeping every historical epoch append-only.

The post-deletion window applies only to `SUBMITTED`, `UNDER_REVIEW`, `SHORTLISTED`, and
`INTERVIEWING`. A `HIRED` Application loses shared-CV access immediately when the Candidate deletes
the CV (`CANDIDATE_CV_DELETED`). Rejection deletes the application-specific `LEGACY_COPY` bytes and
records `application_snapshots.cv_purged_at`, retaining snapshot metadata for traceability and never
touching a `SHARED` candidate library object. A bounded worker retries failures and backfills
already-rejected copies. The inactivity clock is `applications.status_changed_at`, backfilled from
append-only status history, and the inactivity notification goes only to the Company owner and
currently assigned Recruiters.

The legal "unless a retention agreement exists" exception is deliberately deferred; rejection always
purges the legacy copy today. The legal interpretation of retaining snapshot metadata while deleting
bytes remains subject to formal approval.
