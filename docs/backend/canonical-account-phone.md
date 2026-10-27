# Canonical account phone

[ADR 0008](../architecture/decisions/0008-canonical-account-phone.md) and
GW-SR-REC-03 REQ-REC-19 / Q11, Q15 and Q16 define one declared mobile contact per
User. The user approved this bounded schema/API implementation on 2026-10-27.
The subsequent [email-only implementation](employer-onboarding.md) completes immediate-account registration and bound email proof.

## Storage and API

`users.phone_number VARCHAR(20)` is nullable and globally unique when present.
`ck_users_phone_number` accepts only `+84[35789][0-9]{8}`;
`uq_users_phone_number` decides concurrent claims. Multiple phone-less accounts
remain valid. Local `0` and `+84` forms normalize to the same value. A phone is
contact data, never possession evidence.

Candidate `GET/PUT /api/v1/candidates/profile` retains its fields and authorization.
`phoneNumber` returns canonical `+84` or NULL. PUT sets or clears the User phone in
the same transaction as all profile fields. Invalid mobile values return 400;
duplicate/racing claims return safe 409 without partial profile changes. NULL
clears the phone; blank is invalid. Existing Candidate route restrictions for
employer applicants remain in force. No employer phone-change endpoint is added.

`GET /api/me/employer-onboarding` reads `phone` from User and omits `phoneVerified`.
After actual email proof, its stage is `COMPANY_ACTION_REQUIRED` or the existing Company
status; `PHONE_REQUIRED` is removed. Legal preparation, submission/correction and
enhanced employer operations no longer depend on historical SMS proof. Existing
email, active User, Company and membership/permission checks remain in place.

Authenticated POSTs to `/api/me/employer-onboarding/phone/send` and `/phone/verify`
return 410, including absent or malformed bodies, without delivery, enrollment or
writes. Missing authentication still returns 401. Historical contacts, challenges
and proof remain stored; they are never presented as proof of the current phone.

Employer registration now claims the canonical User phone immediately, including
for email-unverified accounts. Pending registrations are retained historical data;
old promotion routes return 410. See the [email-only contract and V84 cutover](employer-onboarding.md)
for normalized-email uniqueness, email proof snapshots and coordinated client changes.

## Controlled cutover

1. Back up the database through the established operator process. Run
   [`canonical-account-phone-preflight.sql`](../../backend/sql/canonical-account-phone-preflight.sql)
   against the pre-migration database using an authorized database client. It is
   read-only and reports invalid, differing same-User, and cross-User contacts.
   The output contains personal contact values: restrict access and never copy
   it into application logs, Git, tickets or public reports. Pending attempts
   appear only as an informational count and never supply an account contact.
2. Resolve every blocking contact through human-reviewed corrections. Do not
   pick an account, merge accounts, delete a contact, or reset a database to
   bypass a blocker. Both verified and unverified old contacts participate.
3. Stop every old backend instance, seeder, direct contact writer and external
   client write path. Re-run the report; prevent writers restarting during
   cutover. Coordinate clients for canonical Candidate responses, 409 handling,
   the removed `phoneVerified`/`PHONE_REQUIRED` fields and SMS 410 responses.
4. Apply `V2026.10.27.83__canonical_account_phone.sql` through Flyway with the new
   backend. The migration locks all three source tables, rechecks conflicts,
   backfills only unambiguous normalized values, and adds database constraints in
   one transaction. A failure rolls back the entire migration. NULL contacts stay
   NULL; blank/non-mobile values block rather than disappearing. Old Flyway
   migrations and historical phone/proof values remain unchanged.
5. Deploy all new writers/readers and the updated development seeder together.
   Verify contact reads/changes, conflict responses and retained authorization
   before reopening writes. Fresh fixtures use User phones and local synthetic
   email proof, with no synthetic phone proof. Reruns preserve existing data.

Do not roll back to an old writer after canonical contacts have changed: its
independent columns are historical and may be stale. Use a reviewed forward fix
or a separately reviewed data-aware rollback. No production/live-data inventory
is claimed by the repository tests. Phone dispute/recovery remains a separate
support-policy decision.
