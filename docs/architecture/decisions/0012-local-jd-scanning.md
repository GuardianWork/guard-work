# Local asynchronous JD analysis

Date: 2026-11-10

Status: Accepted for the local GW-119 scope by the requesting user in conversation.

Use existing Spring MVC/JPA and PostgreSQL for scan persistence and a durable queue.
Keep immutable scan input/report, revision associations and attempts in three new
tables. A Spring scheduled worker uses short lease transactions and calls a loopback
Python adapter outside those transactions. PostgreSQL remains the marketplace store;
the crawler's Neo4j remains the legal retrieval store.

The adapter uses Python's standard library and the separately installed crawler's
existing dependencies. It has a clearly identified stub mode and an opt-in real mode.
The sibling repository is not modified or copied. A shared key protects the local
service boundary; it does not replace GuardWork user authorization. No browser calls
the adapter directly. This selects no new public authentication convention.

Results are advisory and exact-content/version scoped. Persist corpus, profile, mode
and date as immutable provenance. The shared-analysis amendment below supersedes their
original use as automatic reuse keys. Lease fencing prevents stale workers overwriting newer
claims, but provider calls are at-least-once after ambiguous timeouts. Automatic job
decisions, file extraction, production deployment, monetary accounting and replacing
Neo4j with PostgreSQL are outside this decision.

See [the focused scope](../../software-requirements/rec/jd-scan-local-spec.md).

## Shared analysis amendment — 2026-11-22

The requesting user authorized shared analysis for authenticated Candidate, Staff and
Recruiter accounts with current revision access, and explicitly directed implementation
on the existing branch. Reuse is now based on Company and complete canonical JD content,
independent of requester, role, revision version, date or analysis configuration. Keep
Company isolation and exact-version associations; never widen private draft visibility.

A matching completed result wins over unfinished or failed historical runs. Otherwise
reuse active work or the retained failure. Only changed content can create new work;
configuration/date drift is reported as outdated. This avoids duplicate provider spending
across roles at the cost of retaining outdated results until content changes. There is
no user-triggered retry or refresh override.

All creation routes hold the Company row lock through lookup, creation and association,
so existing database schema and lease handling remain sufficient. New shared endpoints
return the public report allowlist and processing state, while existing scoped clients
retain their contracts. The full behavior and verification scope is recorded in the
[shared-analysis specification](../../software-requirements/rec/jd-scan-local-spec.md#shared-authenticated-analysis--session-authorized-amendment-2026-11-22).
