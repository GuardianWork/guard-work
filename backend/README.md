# backend

Spring Boot 4 REST API for **guard-work**.

## Tech Stack

- Java 25, Spring Boot 4.1.1 (spring-boot-starter-webmvc)
- PostgreSQL 18, Flyway for migrations
- Maven

## Getting Started

```bash
# 1. copy environment config and adjust values
cp .env.example .env

# 2. start postgres
docker compose up -d

# 3. run the application
./mvnw spring-boot:run
```

The app boots on `http://localhost:8080`, migrations run automatically via Flyway (`src/main/resources/db/migration`).

## Project Structure

Feature-based packages, each following the **MVC** model. A feature with many files gets
child folders (`model/`, `repository/`, ...); a small feature is kept flat in a single package.

src/main/java/com/guardwork/backend/
├── BackendApplication.java        # Spring Boot entry point
├── common/                        # Shared response wrappers (ApiResponse, PageResponse)
├── user/                          # User entity + persistence (shared)
│   ├── model/
│   │   └── User.java
│   └── repository/
│       └── UserRepository.java        # Spring Data JPA repository interface
├── auth/                          # Auth feature (flat — few files)
├── company/                       # Company management & verification request
│   ├── model/
│   ├── repository/
│   ├── dto/
│   ├── service/
│   └── controller/
├── audit/                         # Append-only audit logging & immutability
│   ├── model/
│   ├── repository/
│   └── service/
└── admin/                         # Admin governance & verification queue/decisions
    ├── dto/
    ├── service/
    └── controller/
```

### Layer responsibilities (MVC)

| Layer        | Responsibility                                                     |
| ------------- | ------------------------------------------------------------------- |
| `model`       | Entity POJOs with JPA annotations (e.g. `User`)                    |
| `repository`  | Data access — Spring Data JPA repository interfaces                 |
| `service`     | Business logic and validation (e.g. `AuthService`)                  |
| `controller`  | Thin HTTP layer, maps DTOs ↔ service                                |
| `dto`         | Request/response objects (flat in the feature package)              |

### Conventions

- One feature = one top-level package (`user`, `auth`, ...).
- Business logic lives in `service`, never in `controller`.
- Services access persistence through repositories and models; services contain no direct SQL.
- Within an approved workflow, a service may compose the narrow shared authorization, audit and
  notification services. A feature service owns the business transaction; audit and notification
  intent join that transaction, while dispatch runs after commit. This exception supports the
  approved Company Membership composition and does not authorize general service-to-service
  dependencies or new shared architecture.
- DB access via Spring Data JPA.

## API Docs

Swagger UI is exposed via [springdoc-openapi](https://springdoc.org/):

- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI spec: http://localhost:8080/v3/api-docs

Admin API `ResponseStatusException`s use the standard response envelope with the original HTTP
status and reason.

## Database

- Schema managed via Flyway: `src/main/resources/db/migration` (`V{version}__{description}.sql`).
- Local Postgres via `docker-compose.yml`, connection settings in `application.properties`.
- Access-token lifetime is configurable with `GUARDWORK_JWT_ACCESS_EXPIRATION_MS` (milliseconds,
  default `900000`, or 15 minutes). Spring Boot imports an optional `.env` file from the backend
  working directory; an exported environment variable takes precedence over the file.

## Tooling

```bash
./mvnw test            # run tests
./mvnw package         # build
```

See [Company Membership backend tests](../docs/backend/company-membership-testing.md) for the isolated PostgreSQL 18 test setup and database safety boundary.
## Company ownership and protected Workspace (GW-002)

Company registration at `POST /api/companies/verification-request` requires an active authenticated
User without an active Company Membership. The transaction creates a pending Company, its first
Owner Membership and an append-only `COMPANY_CREATED` audit fact. Existing Companies retain a null
owner reference and remain inaccessible until the reviewed assignment procedure is implemented.
Company authority stays in persistence; JWT platform roles are unchanged.

`GET /api/auth/me` now includes `joinedAt` in active Company context. `GET /api/companies/me` returns
only the current Company identity and Company Role, or null without membership.
`GET /api/companies/me/verification` exposes the current Owner's dossier, including pending/rejected
status, feedback and version. Existing Company ID/tax-code reads and resubmission aliases require the
current Owner. Inaccessible records return the same not-found result as absent records. Suspended
Companies cannot enter the Workspace; suspended Users cannot use these protected routes.
Anonymous clients must register/login and send a Bearer token before creating or managing a Company.

Flyway `V5__company_ownership.sql` adds membership periods, immutable ownership references, partial
active-membership/Owner uniqueness indexes, append-only membership audit and a deferred cross-row
constraint requiring exactly the declared active Owner. It does not infer owners for legacy rows.
Later milestone tables use new migrations; committed migrations remain immutable.

Protected business transactions use `CompanyAccessService` to lock actor, then Company, then active
membership until commit. Revocation must use the same Company/membership guards and must not acquire
a target User lock after locking the Company, to avoid reversing the creation/admission lock order.

### Isolated PostgreSQL behavioral tests

The `test` profile reads `BACKEND_TEST_DATABASE_URL`, `BACKEND_TEST_DATABASE_USER` and
`BACKEND_TEST_DATABASE_PASSWORD`; its local defaults target `backend_test_db` on port 55432.
Provision a separate PostgreSQL database for tests. Never point this profile at `backend_db`.

```bash
BACKEND_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/backend_test_db \
BACKEND_TEST_DATABASE_USER=backend_test \
BACKEND_TEST_DATABASE_PASSWORD= \
./mvnw -Dspring.profiles.active=test test
./mvnw -Dspring.profiles.active=test package
```

Company HTTP tests use real registration/login, the Security filter chain and Flyway/PostgreSQL.
Fixtures simulate moderation/suspension or a failed audit write; all business results are observed
through HTTP. Migration tests separately prove the PostgreSQL deferred Owner constraints and audit
immutability. No provider, public mailbox or developer database is used.

### Reviewed legacy Owner assignment (GW-003)

No Company ID, tax code, contact mailbox, or evidence URL authorizes ownership. An unassigned
Company stays unavailable to Company management APIs. The restricted local command is available
only to people who can run the backend with its database configuration and who hold a valid,
current active platform `ADMIN` credential. It adds no HTTP claim endpoint or review dashboard.
A Company `ADMIN` role supplies no platform authority.

A platform Administrator with Company verification authority independently checks the applicant's
identity and current legal authority. Accept either a current authoritative registration naming
that legal representative (`LEGAL_REPRESENTATIVE_REGISTRATION`), or current authorization from that
representative plus independently checked registration (`LEGAL_REPRESENTATIVE_AUTHORIZATION`).
Domain email alone, stale records, contradictions, and competing claims stop the review. Escalate
those cases for independent governance review. The command records the authenticated reviewer's
approval and a Company snapshot; it cannot validate real-world documents for the reviewer.

The operation stores only a safe case reference, evidence type, reviewer, applicant, Company
snapshot, decision, and timestamps. Do not put evidence documents, private storage URLs, passwords,
or tokens in case files. This feature does not establish an evidence storage or retention policy.
If the authority evidence has an actual expiry, record it as `evidenceValidUntil` in UTC; use `null`
when the authoritative record has no expiry. Expired evidence cannot be approved or assigned. No
arbitrary validity duration is imposed.

From the `backend/` directory, build using `./mvnw package` and run the following commands. Configure the intended database and JWT signing secret through the
existing Spring configuration before invoking the command. Never use a test profile against a
production or developer database. Obtain short-lived tokens through the existing AUTH login flow;
reviewer and operator must be different active platform Administrators. Supply each token through
`GUARDWORK_OWNERSHIP_TOKEN`, never command arguments or shell history. For example, in Bash:

```bash
read -r -s -p 'Reviewer access token: ' GUARDWORK_OWNERSHIP_TOKEN
export GUARDWORK_OWNERSHIP_TOKEN
```

Create a private case file (`umask 077`, then `chmod 600 review.json`) after checking the current
Company version, verification status, and suspension state using authorized governance records.
Replace the example IDs and snapshot with the reviewed Company's actual values:

```json
{
  "caseReference": "GW-OWNER-2026-001",
  "companyId": 123,
  "applicantUserId": 456,
  "expectedVersion": 7,
  "expectedVerificationStatus": "REJECTED",
  "expectedBanned": false,
  "evidenceType": "LEGAL_REPRESENTATIVE_REGISTRATION",
  "currentRegistrationChecked": true,
  "applicantIdentityMatched": true,
  "currentLegalAuthorityChecked": true,
  "conflictingClaims": false,
  "evidenceValidUntil": null
}
```

Record the approved review as the reviewer:

```bash
java -jar target/backend-0.0.1-SNAPSHOT.jar \
  --guardwork.ownership-command=review --guardwork.ownership-input=review.json
unset GUARDWORK_OWNERSHIP_TOKEN
```

The successful command prints the case reference and `APPROVED`. It binds only an ephemeral
loopback port while reusing the existing application context and shuts down when the command
finishes. Ordinary application startup does not execute ownership operations. Do not override
`server.address` or `server.port` for these commands; a nonlocal or fixed-port configuration is
refused before executing the operation.

The separate authorized operator reads their own token into the same environment variable,
checks the recorded approval against the review case, and supplies a private `assignment.json`
containing only `{"caseReference":"GW-OWNER-2026-001"}`:

```bash
java -jar target/backend-0.0.1-SNAPSHOT.jar \
  --guardwork.ownership-command=assign --guardwork.ownership-input=assignment.json
java -jar target/backend-0.0.1-SNAPSHOT.jar \
  --guardwork.ownership-command=inspect --guardwork.ownership-input=assignment.json
unset GUARDWORK_OWNERSHIP_TOKEN
```

`assign` prints `ASSIGNED`, Company/applicant/membership IDs, reviewer/operator IDs, evidence type,
and decision. `inspect` reads the committed immutable audit, including review and assignment times.
To verify Workspace behavior, use the applicant's existing token with `GET /api/auth/me` and
`GET /api/companies/me`; both identify the new Owner context. The Owner's
`GET /api/companies/me/verification` retains the original dossier, status and rejection feedback.
A suspended Company remains inaccessible after assignment. Ownership assignment increments the
Company version and update time; it preserves tax code, verification, history, suspension and all
existing records. It never verifies the Company or transfers ownership.

The transaction locks authenticated actors and applicant before the Company, then rechecks active
platform authority, reviewer/operator separation, current applicant eligibility, unowned Company,
evidence expiry, and the complete stored Company snapshot. Any failure leaves ownership and
membership unchanged. Audit failure rolls everything back. Repeating `assign`, racing operators,
and racing assignments across Companies cannot create duplicate active ownership or membership.
A changed snapshot or expired evidence requires a fresh reviewed case reference for the same
applicant. An existing competing applicant case always requires separate governance resolution;
the command cannot erase, replace, or automatically settle it.

Flyway `V6__reviewed_legacy_ownership.sql` adds immutable review records and review metadata to the
existing append-only membership audit; it does not backfill or alter unrelated legacy records.
The operational review table is not an evidence repository. `LegacyOwnershipCommandTest` covers
real JWT login, current platform authority, actual command execution, Owner HTTP context,
insufficient/expired evidence, mismatched/stale snapshots, conflicts, suspension, concurrency, and
audit rollback against isolated Flyway/PostgreSQL storage. Available local PostgreSQL 17.11 provides
compatibility evidence; PostgreSQL 18 remains the declared deployment target.

## Recruiter invitation management and private delivery (GW-005)

The current active Owner can list invitations with `GET /api/companies/me/invitations` (`status`,
`page=0`, `size=20`, maximum size 100), issue with `POST` and `{ "email": "recipient@example.vn" }`,
resend with `POST /{id}/resend`, and revoke with `DELETE /{id}`. Issue and resend require a verified,
unsuspended current Company. The server normalizes email by trim/lowercase without looking up the
recipient's account or Company Membership. Issuance does not create membership.

Invitation metadata exposes `UNKNOWN`, `FAILED`, or `PROVIDER_ACCEPTED` delivery state; provider
acceptance does not confirm mailbox delivery. Business data and audit commit before one provider
attempt. Failure leaves the invitation pending. Owner resend is the recovery action and rotates the
private secret and seven Vietnam calendar days of expiry. Expired records are retained and replaced
with new records; revoked or expired records cannot be resent. Hash-generation comparison prevents
an older delivery attempt from overwriting metadata after resend or revocation.

Configure the variables in `.env.example` in the deployment environment or secret store; copying a
file alone does not export these variables. The minimal `CompanyInvitationMailer` adapter uses JDK
`HttpClient` to call [Resend's send-email API](https://resend.com/docs/api-reference/emails/send-email)
without a new dependency. Deployment requires a Resend credential and verified sender, an HTTPS
`COMPANY_INVITATION_BASE_URL` for a frontend confirmation page, and its exact HTTPS origin in
`COMPANY_INVITATION_ALLOWED_ORIGINS`. The URL cannot contain user information, query or fragment.
Timeouts default to 5 seconds for connection and 10 seconds for the request. Redirects and automatic
retries are disabled. Missing or unsafe configuration reports `FAILED`; transport interruption or
timeout reports `UNKNOWN`. No password-reset or general mailbox-verification workflow is changed.
Frontend invitation-page implementation is a separate deployment prerequisite outside this milestone.

The email contains Vietnamese Company/role/expiry information and a private `#secret=` URL fragment.
Owner APIs, audit and persistence expose no raw secret; only a SHA-256 hash of a cryptographically
random 256-bit secret is stored. The adapter never logs credentials, provider bodies or URLs. The
receiving frontend must remove the fragment before loading third-party content and submit the secret
in JSON only after explicit recipient action, as defined in the membership spec.

Flyway `V7__company_invitations.sql` adds invitation history, pending Company/email uniqueness,
secret-hash uniqueness and the audit invitation FK. Invitation issue/resend/revoke audit facts permit
a null subject User because the mailbox need not have an account; a database check continues to
require a subject for all other membership/ownership facts. `V5` is unchanged; `V6` supplies reviewed legacy assignment. Tests use the approved real HTTP/security/PostgreSQL boundary and a
controlled mail adapter, including concurrent issue, exact expiry, failed-audit rollback and slow
provider races. Provider adapter tests use an isolated local HTTP stub; no real mailbox is contacted.

## Recipient admission and minimal inbox (GW-006)

Authenticated recipients send the private mailbox secret only in JSON to
`POST /api/company-invitations/preview`, `/accept` or `/decline`. Acceptance additionally requires
`"confirm": true`; preview and email-link opening grant no membership. The server locks the current
User, Company and invitation and independently checks the normalized account email, secret, pending
state, expiry and verified/non-suspended Company before creating a Recruiter membership. The existing
`emailVerified` flag does not replace possession of the mailbox secret. Responses, including errors,
use `Cache-Control: no-store` and `Referrer-Policy: no-referrer`; DTO diagnostic representations redact
the secret. No request-body logging is enabled. Deployments must keep HTTP-body capture disabled for
these credential-bearing routes.

Admission, invitation consumption, required audit and Owner notification intent commit together.
An accepted retry by the same authenticated invited email returns HTTP 200, the original identifiers
and the membership period's current `ACTIVE`, `LEFT` or `REMOVED` status, including after expiry;
it never creates or restores a membership. A repeated decline returns its original decision receipt.
Invalid/wrong-email/expired/revoked/declined secrets and an ineligible Company return the same
`40410 INVITATION_UNAVAILABLE`. Existing active membership returns generic `40910` with no other
Company details. First acceptance returns HTTP 201. Refetch `/api/auth/me` to obtain current authority.

`GET /api/me/notifications?page=0&size=20` (maximum 100) lists only the authenticated active User's
inbox. `PATCH /api/me/notifications/{id}/read` retains the first read timestamp on retries and gives
the same 404 for missing and other recipients' IDs. Inbox access requires no Company membership;
Company links independently apply the normal current authorization guard. Payloads expose only event
type, Company identity and event/read timestamps. The two supported types are `RECRUITER_ACCEPTED`
and `MEMBERSHIP_REMOVED`.

Flyway `V8__membership_notifications.sql` adds the original accepted membership reference and decision
actor to invitations, durable `notification_outbox` intent and recipient `user_notifications` with
unique `source_event_id`. V5/V7 remain immutable. The first dispatch runs after business commit in
another transaction; a failed inbox write cannot roll back admission. A scoped worker polls at most
100 due events every 30 seconds. Its environment controls are in `.env.example`; the test profile
turns it off except for the deterministic automatic-delivery behavioral test.

Delivery failure retains the durable event and records only `INBOX_WRITE_FAILED`. Delays are 30, 60,
120, 240, 480, 960 and then 1800 seconds, capped at 30 minutes. Five consecutive failures produce a
sanitized warning with event UUID, failure count and category; no User email, secret, credential,
provider body or stack trace is logged by dispatch. Existing delivered events are harmless on replay,
and the unique source event prevents duplicate inbox items if an earlier write outcome is uncertain.

### Trusted notification recovery

Operators investigate sanitized `Membership notification delivery requires recovery` warnings,
check database availability/permissions and repair the failed storage condition. In a trusted
application operations context, invoke the public `NotificationDispatcher.replay(UUID eventId)` seam
for the logged UUID; it advances a still-pending event's due time, retains failure history and dispatches
it independently. `dispatchDue()` runs the normal bounded due batch. These are not public HTTP APIs.
If direct operations access to the application bean is unavailable, a privileged database operator
may set only `next_attempt_at = now()` for that UUID where `delivered_at IS NULL`, then let the worker
retry. Retain the row and its UUID; never delete business history or create a replacement event.
Operators must not change recipient/payload/business rows as a delivery recovery action.

GW-006 HTTP/security/PostgreSQL tests cover explicit acceptance, registration after private delivery,
identity and secret rejection, exact expiry, rotation/revocation, one-User/two-Company races, accepted
inactive-period receipts, inbox isolation/read idempotence, failed audit rollback and a real PostgreSQL
inbox-insert failure that leaves membership active. Recovery is exercised through the public dispatcher
with real persistence; the controlled mail port is the only external delivery substitution.

## Membership removal and departure (GW-007)

`GET /api/companies/me/members?page=0&size=20` lets only the current active Owner list active
membership periods (maximum page size 100), including the Owner. Each row includes membership/User
IDs, Unicode first/last name, email, Company Role, status and join time. The current Company comes
from persisted authority rather than a request Company ID or JWT role claim.

`DELETE /api/companies/me/members/{membershipId}` allows that Owner to end an active Recruiter
period in their own unsuspended Company. It returns `{ membershipId, status: "REMOVED", endedAt }`.
`POST /api/companies/me/membership/leave` lets an active Recruiter end their own period and returns
`LEFT` in the same response shape. Owner removal/departure returns `40912 OWNER_MEMBERSHIP_PROTECTED`;
reserved Admin membership management is unavailable. Unrelated, missing or already inactive removal
targets share the protected not-found response. Departure after membership ends fails the active
membership requirement. Departure is bodyless and applies to the current active period. Since the
request has no period selector, clients should not replay a prior departure after re-admission; such
a request applies to the then-current period. Exact-period retry protection remains a pending proposal
in the draft amendment below.

Both operations retain the original membership row, join time, end time and actor. They lock the actor,
Company and membership guards through commit and recheck current role and suspension. The initial
Company lookup reads only the membership's Company ID, so Hibernate cannot cache an outdated role
before waiting on the Company guard; authority comes from the fresh locked membership row. Removal locks
only the target membership after the Company; it acquires no target User lock. PostgreSQL/Hibernate
uses `FOR NO KEY UPDATE` for the actor guard, allowing target audit/outbox foreign-key checks without
reversing that lock order. Removal and departure racing for the same period have one committed winner.

Removal, required `MEMBERSHIP_REMOVED` audit and one removed-User notification intent commit together;
dispatch then uses the existing independent GW-006 recovery mechanism. Departure records `MEMBERSHIP_LEFT`
audit and sends no departing-User notification. Audit/intent failure rolls back the membership change;
inbox delivery failure retains the ended period and durable intent. Notification retry cannot restore
Company access. A removed User can read/mark their own removal notification without membership, while
Company links independently check current authority.

After commit, `/api/auth/me` reports no active Company Membership and existing JWTs lose protected
Company access. A valid later invitation or Company creation can establish a new membership period;
old accepted receipts continue to report the original period as `LEFT` or `REMOVED`. Original periods
are never reactivated. No migration, Candidate access policy, Job Assignment or Application workflow
is introduced by GW-007. HTTP/security/PostgreSQL18 tests cover these routes, history and eligibility,
Owner protection, removal/departure races, Company suspension and target-role changes committed while
requests wait on the guard, required audit rollback, and inbox failure/replay after access revocation.

### Draft review amendment: exact membership period on departure

The separate review branch prepared
[GW-SR-REC-02-AMEND-01](../docs/software-requirements/rec/company-membership-mvp-spec.md#15-draft-amendment-bind-departure-to-a-membership-period),
which is pending Thinh approval. The implementation follows the approved integration contract above.
The proposed amendment would expose `activeMembership.membershipId` from `/api/auth/me` and require:

```http
POST /api/companies/me/membership/leave
Authorization: Bearer <JWT>
Content-Type: application/json

{ "expectedMembershipId": 81 }
```

The selected period must belong to the actor. A first valid departure ends only that active Recruiter
period; retrying the same own `LEFT` period returns its original ID/end-time receipt without new audit
or notification. Rejoining creates a new period, which an older request cannot end. An old removed,
absent or another User's period shares the protected 404 result. Bodyless/missing/nonpositive IDs
return 400, requiring a coordinated client update. Historical own departure receipts remain available
after the actor joins another Company or the old Company is suspended; they grant no access.

This draft uses existing retained rows and lock order and adds no migration/dependency. It must remain
unimplemented until the API amendment receives named human approval. The same review branch also documents
the approved narrow shared-service composition exception, names the optional authorization lookup
`findCurrentCompanyAccess`, and replaces singular Company-email lookup with an existence query so
multiple historical email rows return the existing generic duplicate-details conflict.

### Scoped request validation for Company Membership

Company registration/correction and invitation requests use Jakarta Bean Validation from
`spring-boot-starter-validation` (Spring Boot manages the provider version). Services evaluate DTO
constraints at the existing workflow checkpoints rather than adding controller `@Valid`: Owner
access and Company eligibility retain their precedence, and direct service callers are validated.
Email is trimmed before `@Email`; the existing dotted-domain rule is retained as its `regexp`.
The provider additionally rejects malformed mailbox syntax such as consecutive dots in an unquoted
local part. Normalization remains trim/lowercase; no mailbox ownership or delivery is inferred.
Company name/tax-code/email limits still count Unicode code points, matching PostgreSQL lengths.
Correction fields remain optional and supplied blank values remain invalid.

Recipient secret constraints map to the same sanitized `40410 INVITATION_UNAVAILABLE` result.
Acceptance-only confirmation constraints are evaluated after secret, identity and eligibility checks;
preview/decline do not require confirmation. Violations are never rendered or logged with rejected
values. Existing status/error envelopes, pagination, business eligibility and database constraints
remain in their services. AUTH/password recovery and the draft departure amendment are unchanged.
No schema migration or client payload update is required.
