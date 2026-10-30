---
status: accepted
date: 2026-10-28
---

# Unified User registration with separate Company setup

Use one User identity for Candidate and recruiting workspaces. An active User can use Candidate features before verifying email. Private recruiting operations, Company submission, and first invitation acceptance require an active User with verified account email. This separates account creation from Company authority without introducing separate account types or legacy email exemptions.

Company roles remain membership-scoped, and Staff Company verification remains separate from personal email verification. Workspace selection grants no authority. Preserve Company approval, suspension, membership, role, and job-assignment checks.

Company setup accepts a complete multipart dossier and document in one explicit submission. Create the pending Company and first Owner atomically. Do not persist unfinished Company forms. Rejected correction retains Company identity, immutable tax code, private evidence, declarations, and review history. Separate employer signup, mandatory phone OTP, provisional saves, and standalone document uploads are retired with HTTP 410.

Account-email proof uses explicit request-only delivery, a six-digit single-use code valid for 10 minutes with at most five failed attempts, and a 60-second resend cooldown (User-approved amendment, 2026-10-30; invitation links remain unchanged). `/api/auth/me` reports `emailVerified` and `recruitingEmailEligible`. Eligibility is derived from current account state; no cohort marker or separate Candidate-access flag is stored or returned.

Migration `V2026.10.28.84__unified_registration_email_proof.sql` creates hashed account-email proof storage. Baseline installations preserve existing verification values and account/Company records. Disposable databases with an earlier checksum for migration 84 or the removed migration 85 require rebuilding. Deploy compatible backend and clients together; older registration writers that automatically verify email must not run alongside this flow.

This decision replaces ADR 0005's separate-registration, phone-proof, and restricted-Candidate workflow. See [GW-SR-AUTH-03](../../software-requirements/auth/unified-registration-and-recruiting-access-spec.md), its [implementation contract](../../software-requirements/auth/unified-registration-implementation-contract.md), and the [operational guide](../../backend/unified-registration.md).

The 2026-10-30 account-only code-entry amendment retains the endpoint and `emailSecret` field. Migration `V2026.10.30.85__account_email_verification_codes.sql` persists the attempt budget and invalidates outstanding account links; deploy the separate client code-entry UI with the backend. Existing verified flags and invitation behavior are preserved.
