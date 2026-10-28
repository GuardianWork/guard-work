---
status: accepted
date: 2026-10-18
---

# Staged employer onboarding and retained verification evidence

The user approved the product journey, separate pending registration and derived CHECKED direction, then confirmed the concrete implementation contract with “chốt”. This records that human authorization; it does not select an SMS vendor or a production retention/legacy-rollout policy.

Employer registrations remain separate from official Users until the applicant completes exact-attempt email proof and confirms their credentials. Official User email remains unique. Newly enrolled applicants have explicit personal-verification/onboarding state; it is not a global Recruiter role. Existing Candidate registration remains compatible.

Use the existing Spring MVC/JPA/PostgreSQL architecture and additive scoped APIs. Enforce onboarding eligibility on backend requests and validate personal proof again at legal-submission boundaries. Keep Owner feedback/correction reachable before Company approval. Roll out the new employer gates to the new journey and Companies with retained verification attempts; legacy Company migration remains a separately required rollout decision.

Keep PENDING/VERIFIED/REJECTED as Company eligibility states. Derive processing status PENDING/CHECKED and review outcome in responses. CHECKED never grants approval by itself.

Retain legal document bytes privately in PostgreSQL, with a 10 MiB limit, basic PDF/JPEG/PNG signature checks, a content hash, and authenticated attachment downloads. Retain immutable submission/declaration snapshots and their decisions. Database guards protect evidence from overwrite/deletion. A future approved retention policy will require an explicit controlled disposal design; current retention is not a production policy approval.

Record a scoped decision-email delivery intent in the decision transaction. Send through a separate worker after commit, using the existing Resend/JDK HTTP integration style, bounded retries and stable provider idempotency keys. Do not redesign the membership inbox or invitation mail. Actual mailbox delivery is not implied by provider acceptance.

Build the scoped onboarding and review interface in dependency-free HTML/CSS/JavaScript, served on the backend origin through Maven static resources. Keep session tokens in memory and require explicit sign-in after email proof. No dependency was added.

No SMS provider was selected. The external SMS seam fails closed when no real adapter is installed; tests use an isolated fake. Never expose an OTP in production responses/logs or call undelivered proof verified.

References: [product specification](../../software-requirements/rec/company-onboarding-verification-enhancement-spec.md), [implementation contract](../../software-requirements/rec/company-onboarding-implementation-contract.md).

## Scoped supersession — 2026-10-28

The user explicitly approved [ADR 0008](0008-unified-registration-and-company-setup.md) and the [unified registration implementation contract](../../software-requirements/auth/unified-registration-implementation-contract.md) with “Approve”. Shared active-User registration and requested account-email proof for new Users replace separate pending employer promotion, mandatory phone proof, Candidate restrictions and provisional Company saves. Complete multipart Company submission/correction persists evidence only on submission. Existing accounts remain exempt from new personal email gates. Private evidence, immutable Company review/declaration history and decision delivery remain; the earlier approval record above is preserved. Legacy routes retire under the replacement’s explicit compatibility/deployment contract, without data reset or automatic promotion.
