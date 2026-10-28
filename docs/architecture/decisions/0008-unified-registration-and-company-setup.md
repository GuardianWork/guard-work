---
status: accepted
date: 2026-10-28
---

# Unified User registration with separate Company setup

The design interview settled on one User identity for Candidate and recruiting workspaces. For new Users, actual account email proof gates private recruiting operations, Company submission and invitation acceptance; Candidate access does not require that proof. Existing accounts remain unchanged. Company roles remain membership-scoped, and Staff approval of the Company remains separate from personal email verification.

Replace separate employer registration and mandatory phone OTP with explicit Company dossier submission from an authenticated eligible User. Do not save unfinished Company forms. Complete submission creates the pending Company and first Owner atomically; preserve private evidence, declarations, rejected-dossier correction and review history. This separates account creation from establishing authority to recruit for a Company, while allowing the same person to seek jobs.

This decision replaces ADR 0005's separate-registration, personal-phone and restricted-Candidate journey within the approved replacement scope. It also replaces GW-78's personal-email bypass for new Users. It preserves Company approval and membership/role/assignment checks. Retiring the obsolete endpoints requires coordinated client updates; existing database records are not implicitly deleted.

The individual product decisions are recorded in [GW-SR-AUTH-03](../../software-requirements/auth/unified-registration-and-recruiting-access-spec.md). The user approved the consolidated product direction with “LGTM” and requested ticket preparation. That ticket-preparation request did not itself authorize implementation. On 2026-10-28 the user subsequently replied “Approve” to the concrete contract and its consequential API/schema/rollout/test choices; this ADR now records that explicit human decision. Human owner/approver attribution is User (name requested; pending response).

The [approved concrete implementation contract](../../software-requirements/auth/unified-registration-implementation-contract.md) records the additive persisted rollout boundary, atomic complete multipart dossier and legacy retirement choices. Its deployment requires stopping older account writers because their automatic verified flags cannot safely coexist with the new proof contract. Approval covers the 24-hour single-use proof, 60-second resend cooldown, request-only delivery, multipart submission/410 retirement, additive schema and HTTP/service/PostgreSQL verification seams. Retained Companies, memberships and evidence are preserved; no dependency or data reset is introduced.

Implementation on 2026-10-28 uses migration `V2026.10.28.84__unified_registration_email_proof.sql` for the persisted cohort and hashed proof, with complete multipart Company submission/correction and inert HTTP 410 compatibility routes. The old signup/phone runtime is removed while its historical records and private review evidence remain. The complete backend suite passed 308 tests. [Operational documentation](../../backend/unified-registration.md) describes the implemented flow and coordinated rollout; no browser client is claimed because its source is outside this checkout. This records completion of the accepted decision, not a new architecture choice or a new retention policy.
