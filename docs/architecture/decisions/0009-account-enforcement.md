# Account enforcement and session invalidation

On 2026-10-30, the requesting human approved the GW-92 implementation contract in
[GW-SR-ADM-02](../../software-requirements/adm/account-management-spec.md), including
its schema additions and authentication changes for UC-18–20.

Use PostgreSQL as the enforcement authority. Version access sessions per user and
revoke refresh tokens in the same transaction as ban state, retained ban history,
posting suspension, and attributable immutable auditing. The JWT filter checks
persisted active state and session version; unavailable storage denies access.
New JWTs include `sessionVersion`; older tokens without it mean zero. Stop older
application/token-issuer instances during rollout so they cannot bypass this check.
Staff authority requires both signed and current persisted roles. A stale role
cannot retain or acquire Staff authority; existing portal restrictions still apply.

Company enforcement invalidates every active member role, while personal user
accounts remain active. Company access keeps the existing Owner status/evidence
read exception. Lock Company before participating users, users by ascending ID,
and postings by ascending ID. Login, refresh, publication, and membership writes
serialize against these boundaries. Concurrent lock conflicts may return 409.

Temporary-ban expiry is reconciled by a bounded scheduled worker and at enforcement
boundaries. Read-only Staff inspection derives effective state without settling it.
Revoked credentials stay revoked after expiry; Company expiry also invalidates
sessions issued during the ban. Expiry history records a system end reason rather
than inventing a Staff audit actor.

Represent posting suspension independently with `staff_suspended`; retain the
existing publication lifecycle. Company unban and expiry do not clear suspension.
UC-21 must define restoration separately. No cache, distributed transaction,
dependency, role change, verification grant, or automatic republication is added.
