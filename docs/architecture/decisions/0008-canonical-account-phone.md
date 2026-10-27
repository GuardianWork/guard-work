---
status: accepted
date: 2026-10-27
---

# One declared phone per account

Following [ADR 0007](0007-email-only-employer-onboarding.md), the user confirmed Q11/Q15/Q16: Candidate and employer contacts share one canonical normalized phone per User, with database-backed global uniqueness and NULL permitted for existing phone-less accounts. This replaces independent current-contact numbers in each profile; it does not prove phone possession. Candidate profile updates change or clear the shared number under existing authorization, and no new employer phone-change endpoint is selected.

The user chose blocking conversion over automatically selecting an account/number when old data contains cross-User collisions, differing same-User contacts or invalid phones. Keep historical values/proof without presenting them as verification of the canonical current number. This makes uniqueness and contact edits explainable at one boundary, at the cost of coordinating Candidate APIs, fixtures, old writers and migration. The original exact schema/migration proposal is recorded in the [implementation plan](../../software-requirements/rec/company-onboarding-email-only-implementation-plan.md); the bounded implementation approval below selects its canonical-phone portion.


## Bounded implementation approval — 2026-10-27

The user approved nullable `users.phone_number VARCHAR(20)` with global UNIQUE
and the canonical Vietnamese-mobile CHECK, a read-only preflight and blocking
backfill, atomic Candidate reads/updates, coordinated employer contact paths and
fixtures, and authenticated 410 retirement of SMS endpoints. The implementation
preserves historical contacts/proof, omits current `phoneVerified`, and removes
SMS prerequisites so endpoint retirement does not strand enrolled accounts.
The broader immediate-account/email-only registration replacement remains separate.
See [storage, API and cutover instructions](../../backend/canonical-account-phone.md).
