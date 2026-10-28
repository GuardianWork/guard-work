# Complete Company setup and correction

Implements UREG-004–010 and UREG-014 from the [approved unified registration contract](../software-requirements/auth/unified-registration-implementation-contract.md). Account registration and Company establishment are separate actions. No unfinished Company form or preuploaded document is saved for the replacement journey.

## Initial submission

An authenticated active User sends `POST /api/companies/verification-request` as multipart/form-data with exactly one `file` and an application/json `dossier` part:

```json
{
  "name": "Công ty Ánh Dương",
  "taxCode": "0312345678",
  "legal": {
    "workAddress": "Địa chỉ đăng ký kinh doanh",
    "submitterPosition": "Giám đốc",
    "representation": "LEGAL_REPRESENTATIVE",
    "declarationAccepted": true
  }
}
```

The file is a nonempty PDF, JPEG or PNG, maximum 10 MiB, validated using its byte signature. Name/tax/address/position limits are 255/50/1000/200 Unicode characters after trimming. Representation is `LEGAL_REPRESENTATIVE` or `AUTHORIZED_REPRESENTATIVE`. No separate Company email, phone OTP, gender or provisional form-save request is accepted as a prerequisite. The backend derives contact from the account and snapshots the server-owned declaration.

All Users require verified account email; there is no legacy exemption. All Users must have no active Company Membership. The request atomically creates a pending Company, first Owner membership, private retained document and immutable verification attempt. Validation, uniqueness or audit failures roll back the complete submission. The response remains HTTP 201 with the existing `CompanyVerificationResponse` envelope.

## Rejected correction and review

Only the current Owner may correct a REJECTED Company. Send the same complete multipart dossier and a new file to `PUT /api/companies/me/verification` or `PUT /api/companies/{id}/resubmit`, including current nonnegative `expectedVersion` in the JSON. Tax code is immutable and is not an editable correction field. A correction retains Company/Owner identity and previous evidence, adds a distinct immutable document and attempt, and returns the Company to PENDING. PENDING/VERIFIED dossiers cannot be edited; stale versions cannot overwrite current data.

Staff review, immutable decision history and the existing after-commit decision-email retry worker remain in place. Decision mail uses the submitting User's current account email. Owner status/feedback/history remain available through the existing GET endpoints; they grant no publishing authority. Job submission/publication still require a verified Company.

## Access and errors

Own Company/status responses add `companySuspended`. An active Owner can read their suspended Company's minimal status, rejection feedback/history and authorized retained evidence. This is a read exception only: correction, jobs and other private Company operations remain blocked. Inactive or unrelated memberships and Recruiters cannot read Owner evidence; document lookup remains a non-enumerating 404. Private downloads retain `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.

Malformed/missing dossier or file parts, invalid fields/signatures and repeated files return the existing envelope with HTTP 400 and `40001 INVALID_INPUT`; oversize files return 413. Missing account-email proof returns 403 `40335 EMAIL_VERIFICATION_REQUIRED`. Duplicate tax code, active membership conflict, non-rejected correction and stale versions retain their approved errors. Legacy JSON-only submission and correction contracts return HTTP 410 `41030 ONBOARDING_RETIRED` without mutation; clients must migrate to complete multipart submission.

No additional Company schema or dependency is introduced. Existing document/attempt/declaration and membership constraints are reused. Stop older registration/Company writers for the coordinated unified-registration migration and client rollout described in the implementation contract. Existing Companies, memberships, files and review decisions are retained.
