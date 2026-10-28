# Owner Company verification correction

The [approved unified registration contract](../software-requirements/auth/unified-registration-implementation-contract.md)
replaces partial JSON/URL correction. See [complete Company setup](company-setup.md)
for the current fields and file bounds.

An active current Owner can inspect feedback with GET `/api/companies/me/verification`
and history with GET `/api/companies/me/verification/history`. Owners of suspended
Companies retain authorized status/history/evidence reads; correction and other private
Company operations remain blocked. Recruiters, unrelated and inactive memberships gain
no Owner evidence access.

Correct a REJECTED Company through PUT `/api/companies/me/verification` or the equivalent
PUT `/api/companies/{id}/resubmit`, sending a complete multipart dossier and new license
file. The JSON `dossier` contains `name`, nonnegative `expectedVersion`, and complete
`legal` address/position/representation/declaration fields. Separate contact email and
arbitrary certificate URLs are not inputs. Tax code, Company identity and ownership
remain unchanged. Old JSON-only requests return 410 without mutation; no partial save
or pending-dossier edit is available.

New Users need account-email proof; legacy Users retain their exemption. Correction
rechecks current User, Owner and Company under actor/Company/membership locks. Only
REJECTED becomes PENDING. Stale versions return 409 `40022 CONCURRENT_MODIFICATION`;
other states return 400 `40012 COMPANY_NOT_REJECTED`. Invalid requests roll back the
entire correction, including new document bytes.

Success increments the Company version, clears current rejection feedback, and inserts
a distinct immutable document/declaration/attempt while preserving former submissions,
decisions and audit. Staff review remains separate; correction never approves a Company.
Decision mail uses the submitting Owner's account email through the existing retry worker.

Both aliases return `OwnerVerificationResponse`, including `companyId`, name/tax/contact,
private certificate reference, verification/processing/outcome, rejection reason, version,
timestamps and additive `companySuspended`. The ID alias selects only the current
Owner's Company. Unknown JSON properties confer no authority or tax identity changes.

Current correction, complete-dossier and journey HTTP/PostgreSQL tests verify versions,
state/role isolation, rollback, immutable identity/evidence, retained history, suspension
and deterministic guard races. No additional Company schema or dependency is introduced.
