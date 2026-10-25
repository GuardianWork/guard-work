# Owner Company verification correction (GW-004)

Requirement: [Company Membership MVP, Section 6.2](../software-requirements/rec/company-membership-mvp-spec.md#62-owner-verification-management).

An active Owner can read their current Company dossier with `GET /api/companies/me/verification`
and correct a rejected dossier with `PUT /api/companies/me/verification`. Both require the current
persisted Owner Membership, an active User and a non-suspended Company. Pending and rejected
Companies remain accessible for verification management; correction returns the Company to review
and does not verify it or enable invitation management.

The correction JSON accepts optional `name`, `email`, and `registrationCertificateUrl`, plus required
`expectedVersion`. Omitted editable fields retain their current values; provided values must be
nonblank. Names and emails are limited to 255 Unicode characters; email also has the existing
format validation and trim/lowercase normalization. Company ID, tax code and Owner cannot be
changed through the request. Unknown JSON properties do not confer authority or modify those fields.

Only `REJECTED` can transition to `PENDING`. Success clears current rejection feedback, increments
`version`, updates `updatedAt`, and retains the existing Company identity, Owner relationship,
moderator identity/timestamp and append-only moderation audit. Stale versions return HTTP 409
`40022 CONCURRENT_MODIFICATION`; another Company state returns HTTP 400 `40012 COMPANY_NOT_REJECTED`.
Invalid corrections and authorization changes roll back all changes. Actor, Company and membership
locks are retained through transaction commit, so newer eligibility changes committed first are
rechecked before any correction is applied.

## Client compatibility

`PUT /api/companies/{id}/resubmit` remains a compatibility route. The ID is only a selector: it
must match the current persisted Owner's Company. It performs the same correction and returns
the same `OwnerVerificationResponse` dossier as the current-Company GET/PUT routes:
`companyId`, `name`, `taxCode`, `email`, `registrationCertificateUrl`, `verificationStatus`,
`rejectionReason`, `version`, `createdAt`, and `updatedAt`. Clients of the older resubmission response
must replace `data.id` with `data.companyId`; moderator details are not present in this Owner dossier.
Existing Company creation and ID/tax-code GET response shapes remain available.

The current Owner attempting new Company registration while their existing Company is rejected
receives privacy-safe HTTP 409 `40902 RESUBMISSION_REQUIRED`, with guidance to correct that existing
Company. Other active-membership creation conflicts retain `40910 ACTIVE_COMPANY_MEMBERSHIP_EXISTS`.
Unrelated Users attempting registration with an already registered tax code or email receive the
generic `40901 DUPLICATE_TAX_CODE` response, which does not reveal its status, Owner or dossier.
Correction email collisions use that same generic conflict. Tax-code uniqueness is enforced by
PostgreSQL; contact-email collision checking preserves the existing application rule and V4 index.
This ticket does not introduce a schema migration.

## Verification evidence

`CompanyCorrectionHttpTest` exercises real registration/login, Security, HTTP and PostgreSQL.
Coverage includes Unicode correction/feedback, immutable identity and Owner, validation rollback,
private errors for Recruiters/unrelated Users/inactive periods, platform-role separation, suspended
accounts/Companies, old JWTs, stale versions, invalid state, replacement registration, alias shape,
moderation metadata, concurrent corrections and deterministic blocked User/Company guard races.
Fixture SQL only establishes moderation, membership or suspension conditions and coordinates guard
races; business outcomes are observed through HTTP. Existing ownership tests are retained.

Run the documented backend test command with the test profile and an isolated database. Local
compatibility verification used native PostgreSQL 17.11 at port 55432 with database
`backend_test_db_gw004`; deployment continues to target PostgreSQL 18. Never use `backend_db`.
