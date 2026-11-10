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
and date in the reuse identity. Lease fencing prevents stale workers overwriting newer
claims, but provider calls are at-least-once after ambiguous timeouts. Automatic job
decisions, file extraction, production deployment, monetary accounting and replacing
Neo4j with PostgreSQL are outside this decision.

See [the focused scope](../../software-requirements/rec/jd-scan-local-spec.md).
