# Private user reports and Staff inspection

On 2026-11-01 the requesting maintainer approved the [GW-99 contract](../../software-requirements/adm/user-report-intake-contract.md),
including report submission, Staff inspection, private evidence and additive schema.
Reports retain a type and string target ID because Company/Job IDs are Long values
and Candidate profile IDs are UUIDs; target visibility is validated on submission,
while later target privacy changes preserve the complaint without exposing a label.
This trades polymorphic foreign-key enforcement for retained references across
existing identifier types, with no cascade deletion or fabricated target snapshots.

Evidence uses bounded PostgreSQL bytea storage, following the existing private
Company document pattern and avoiding a new storage dependency. It increases
database storage/backup size; retention policy and malware scanning remain separate
work. Submission, evidence and safe audit metadata commit atomically. Staff reads
never start processing or apply sanctions; adjudication requires a separate contract.

An exact `/api/reports` DispatcherServlet mapping shares the existing application
context and security filters but has a scoped 16 MiB request/5 MiB file allowance.
The default servlet's Company limits remain unchanged. This avoids raising upload
limits across unrelated routes. A path-scoped exception mapper handles size failures
before controller selection, preserving the existing Company 413 contract.
