---
status: accepted
date: 2026-10-27
---

# Immediate employer accounts with email-only eligibility

The user confirmed Q1–Q5 and Q7–Q9 of the onboarding design interview and explicitly deferred Q6. This product decision partially supersedes [ADR 0005](0005-staged-employer-onboarding.md): successful registration creates an official email-unverified account that can sign in to a restricted employer workspace. Actual email proof unlocks Company preparation/submission and valid Recruiter invitation acceptance. SMS is removed from onboarding and employer eligibility, including for accounts already subject to those gates. Code still implements the earlier journey; this record does not approve an unspecified replacement schema or API.

Email and normalized declared phone must be unique across existing accounts, including unverified accounts. The user chose manual support for disputed phone reservations and no automatic release at email-proof expiry, accepting that a declared phone is not possession evidence. This replaces verified-only phone ownership and non-reserving pending claims; support evidence, recovery authority and existing-data collision resolution need concrete review before production.

Employer Applicant is a journey term, not a platform role. Registration Company name grants no membership. Valid invitation acceptance after email proof creates ACTIVE RECRUITER membership; complete new-Company submission atomically creates PENDING Company, ACTIVE OWNER and retained attempt. Recruitment still requires VERIFIED Company and current authorization. The already-ACTIVE Recruiter-to-new-Owner case (Q6) remains undecided; no mandatory departure flow or membership-constraint change is selected.

Retain prior legal evidence, actual proof history, moderation safeguards and unaffected delivery/storage choices. Remove SMS gates for already-enrolled accounts without newly enrolling unrelated legacy accounts. New migrations/API compatibility, pending-attempt handling and global phone data ownership require an explicit technical amendment. See [GW-SR-REC-03](../../software-requirements/rec/company-onboarding-verification-enhancement-spec.md) and its [implementation contract](../../software-requirements/rec/company-onboarding-implementation-contract.md).
