# Staff Job Posting suspension and event history

On 2026-10-31 the requesting maintainer approved the focused [GW-93 contract](../../software-requirements/adm/staff-job-management-contract.md) for UC-13 and UC-14. This extends GW-78 with Staff-only `PUBLISHED → SUSPENDED` and a read-only posting-event endpoint; unrelated Staff capabilities remain Draft.

Reuse the existing persisted Staff check, actor → Company → Job Posting lock order, optimistic version, job audit events, platform audit and response/pagination contracts. A hide commits the job state and both audit records together. Reuse event facts for reason/status/actor/time rather than introducing a parallel suspension store. A new immutable Flyway migration adds `SUSPENDED` to the existing status check. No dependency or notification mechanism is added.

Suspended postings return a content-free public 404, and public job details set `Cache-Control: no-store`. Company authoring/submission and lifecycle actions cannot restore a suspended posting. Pending reviews can complete without changing suspension; withdrawal and authorized historical reads remain possible. Expiry leaves suspension intact. UC-21 restoration is separate work.

The current backend has no public job search/listing or server content cache to invalidate. Future integrations must enforce this visibility boundary and define invalidation; already delivered content and responses cached before this rollout cannot be recalled. Roll out compatible clients for the additive private `SUSPENDED` status; older writers must not run concurrently. Rollback requires reviewed handling of suspended records, not silent republication or mapping to Company pause.
