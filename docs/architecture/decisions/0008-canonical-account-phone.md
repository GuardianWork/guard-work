---
status: accepted
date: 2026-10-27
---

# One declared phone per account

Following [ADR 0007](0007-email-only-employer-onboarding.md), the user confirmed Q11/Q15/Q16: Candidate and employer contacts share one canonical normalized phone per User, with database-backed global uniqueness and NULL permitted for existing phone-less accounts. This replaces independent current-contact numbers in each profile; it does not prove phone possession. Candidate profile updates change or clear the shared number under existing authorization, and no new employer phone-change endpoint is selected.

The user chose blocking conversion over automatically selecting an account/number when old data contains cross-User collisions, differing same-User contacts or invalid phones. Keep historical values/proof without presenting them as verification of the canonical current number. This makes uniqueness and contact edits explainable at one boundary, at the cost of coordinating Candidate APIs, fixtures, old writers and migration. The exact `users.phone_number` schema and migration are proposed in the [implementation plan](../../software-requirements/rec/company-onboarding-email-only-implementation-plan.md) for technical review; this decision does not claim they are implemented.
