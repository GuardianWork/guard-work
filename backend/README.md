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

# 4. in another terminal, view mock account credentials and seed completion
docker compose logs -f seed
```

The app boots on `http://localhost:8080`, migrations run automatically via Flyway (`src/main/resources/db/migration`).

### Public and Staff frontend proxies

The public Vite frontend runs at `http://localhost:5137` and Staff at
`http://localhost:5138`. Both proxy `/api` to `http://localhost:8080` while preserving
the prefix. Each proxy must overwrite `X-Guardian-Portal` with `public` or `staff`.
Both call `POST /api/auth/login` with `{ "usernameOrEmail": "...", "password": "..." }`.
Responses retain `{ status, message, data }`, including the JSON token pair. Send
`Authorization: Bearer <accessToken>` for protected calls. Frontend token storage
remains `sessionStorage`; this change introduces no cookie session.

Public login accepts Platform `USER`; Staff login accepts Platform `STAFF`.
`/api/staff/**` still checks the signed Platform role: invalid/missing/expired tokens
return 401, insufficient role or wrong access-token portal returns 403. A portal
header cannot grant a role. Refresh/logout also check the stored portal; a mismatch
returns 401 without consuming the session. Omitted headers retain Swagger and older
clients by using authenticated role/session context. Legacy access tokens without a
portal claim use their signed role. See the [portal contract](../docs/software-requirements/auth/portal-routing-contract.md).

Same-origin Vite proxy calls require no direct browser CORS. For direct cross-origin
calls, set `GUARDWORK_CORS_ALLOWED_ORIGINS=http://localhost:5137,http://localhost:5138`
(or the exact deployed frontend origins). The default allowlist is empty; wildcards
are rejected. Allowed headers are `Authorization`, `Content-Type`, `X-Guardian-Portal`
and `X-Request-ID`; no credentialed cookies are enabled. CORS is not proxy authentication.
Deployment gateways must remove client-provided portal values and overwrite them
from trusted routing; prevent public access that bypasses the gateway if portal
source attribution is required.

Migration `V2026.10.24.82__bind_refresh_tokens_to_portal.sql` backfills existing refresh
sessions by Platform role and constrains new sessions to `public`/`staff`. Run it
with this backend release and stop old refresh-token writers before migration.
Earlier migrations are unchanged. Local Platform fixtures now use `demo_staff` /
`staff@demo.guardwork.test`; previously seeded `demo_admin` identifiers remain usable
with the migrated `STAFF` role. Company administrator fixtures retain Company `ADMIN`.

### Local mock data and login

Run the commands in this section from `backend/`. Keep the backend running in one
terminal and run Docker log/status commands in another.

#### Seed files

| File | Responsibility |
| --- | --- |
| [`docker-compose.yml`](docker-compose.yml) | Starts PostgreSQL and the local `seed` service; supplies database credentials and read-only mounts. |
| [`docker/dev-seed/seed.sh`](docker/dev-seed/seed.sh) | Checks the enable flag, waits for Flyway migrations, runs the SQL, and prints the account table after successful seeding. |
| [`docker/dev-seed/seed.sql`](docker/dev-seed/seed.sql) | Inserts demo accounts, Candidate/employer profiles, a Company, memberships and GW-93 job scenarios in one transaction. |
| [`.env.example`](.env.example) | Documents local database settings and `GUARDWORK_DEV_SEED_ENABLED`. |

Edit `seed.sql` to change fixture definitions; existing rows are preserved on reruns,
so editing an INSERT does not update previously seeded accounts or their passwords.
Keep development fixtures separate from Flyway schema migrations.

#### Startup and completion

The development Compose file starts PostgreSQL and a one-shot `seed` service using the
same PostgreSQL image. On a fresh volume, start the backend so Flyway can create the
schema; `seed` waits for all committed migrations before inserting fixtures. Docker
alone does not run Flyway or start the API. With an already migrated database, seeding
can finish immediately. Wait for `Local mock data ready` in `docker compose logs -f seed`
before logging in. An SQL error causes the entire seed transaction to roll back and
the service to exit unsuccessfully; inspect its logs if the ready message is absent.
Seed and migration bind mounts are read-only with shared SELinux labels (`ro,z`)
so the service can read them on SELinux-enabled Docker hosts.

```bash
docker compose ps -a seed
docker compose logs --tail 50 seed
```

The expected completed state is `Exited (0)` with `Local mock data ready` in the log.
The seed container is a one-shot task and does not need to stay running.

#### Demo credentials and login request

All newly created mock accounts use **`GuardWork123!`**. Log in with either username
or email:

| Account | Username | Email | Platform role | Company role |
| --- | --- | --- | --- | --- |
| Candidate | `demo_candidate` | `candidate@demo.guardwork.test` | `USER` | None |
| Platform staff | `demo_staff` | `staff@demo.guardwork.test` | `STAFF` | None |
| Company owner | `demo_owner` | `owner@demo.guardwork.test` | `USER` | `OWNER` |
| Company administrator | `demo_company_admin` | `company-admin@demo.guardwork.test` | `USER` | `ADMIN` |
| Recruiter | `demo_recruiter` | `recruiter@demo.guardwork.test` | `USER` | `RECRUITER` |
| Email-unverified User | `demo_employer_unverified` | `employer-unverified@demo.guardwork.test` | `USER` | None |

```bash
curl http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"usernameOrEmail":"demo_owner","password":"GuardWork123!"}'
```

In Swagger UI (`http://localhost:8080/swagger-ui/index.html`) or Postman, send
`POST /api/auth/login` with `Content-Type: application/json` and this body:

```json
{
  "usernameOrEmail": "demo_owner",
  "password": "GuardWork123!"
}
```

The login field is `usernameOrEmail`; `username` and `email` are not substitutes for
that request field. A successful response is HTTP 200 with `data.accessToken` and
`data.refreshToken`. Use the access token as `Authorization: Bearer <accessToken>`
when calling `GET /api/auth/me` to inspect the platform role and active Company membership.

#### Seeded data and reruns

Fixtures include a private Candidate profile, one verified demo Company, three active
Company memberships, and employer profiles with synthetic email proof, plus one
email-unverified User. Current phones are canonical User contacts; fresh fixtures
do not fabricate phone proof.
These are direct local fixtures, so no OTP or email delivery is needed. They preserve
the platform/Company role separation in GW-SR-AUTH-01 and GW-SR-REC-02; available
operations still depend on implemented APIs. The certificate URL is a placeholder;
no legal dossier, real document, CV file, or verification delivery is created. Synthetic Job Postings and their histories are included for GW-93 testing.

Each `docker compose up -d` reruns an exited seed service and inserts missing fixtures.
It preserves existing passwords, statuses, Company state, profiles, and membership history;
it does not reset changes made during manual testing. Previously changed credentials
must be used as changed. Identifier collisions fail rather than granting privileges
to a different account. `docker compose restart postgres` only restarts PostgreSQL;
run `docker compose up -d seed` to rerun the seeder separately.

```bash
# Rerun the seed service and inspect its result.
docker compose up -d seed
docker compose logs -f seed
```

This Compose environment is local development only: PostgreSQL binds to loopback and
mock passwords are public. Set `GUARDWORK_DEV_SEED_ENABLED=false` in `.env` to disable
future seeding (existing fixtures remain). The isolated test Compose file never seeds
these accounts. No schema migration, API contract, or application authorization rule
is changed. Do not delete an existing database volume to enable seeding.

`POSTGRES_DB`, `POSTGRES_USER`, and `POSTGRES_PASSWORD` configure both development
Compose and the backend. `POSTGRES_HOST` is the host used by the backend (default
`localhost`); `POSTGRES_LOCAL_PORT` selects the published host port and takes precedence
over the backend-only fallback `POSTGRES_PORT`. Database/user/password changes on an
existing volume require matching database administration; container environment values
alone do not change previously initialized PostgreSQL credentials.

#### Troubleshooting login

| Symptom | What to check |
| --- | --- |
| Seed keeps waiting for migrations | Start the backend and confirm Flyway completed. Backend and Compose must use the same database. Check database connectivity if the backend has finished migrating but the seed still waits. |
| Seed exits unsuccessfully | Read `docker compose logs seed`; SQL errors roll back all fixture inserts. Resolve the reported collision or constraint failure before rerunning. |
| Log says `Local mock data disabled` | Set `GUARDWORK_DEV_SEED_ENABLED=true` in `.env`, then run `docker compose up -d seed`. |
| HTTP 400 during login | Send JSON with the exact fields `usernameOrEmail` and `password` as shown above. |
| HTTP 401 during login | Check the account table and ready message in the seed log, the target API/database, and the password. Rerunning seed preserves a password changed earlier. |
| HTTP 403 on a protected API after login | Check the account's current platform and Company roles using `/api/auth/me`. Company Admin and Recruiter accounts have platform role `USER`. |
| Connection refused | Start the backend and confirm its HTTP port. Docker starts the database and seed task; it does not start the API. |

Do not remove the development database volume to troubleshoot a login failure.

For local application DEBUG logging, run
`SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run`. Default environments use
INFO. Logs go to the console and correlate requests through `X-Request-ID`.
See the [backend logging standard](../docs/convention/backend-logging.md) for
configuration, privacy rules, and examples.

## Project Structure

Feature-based packages, each following the **MVC** model. A feature with many files gets
child folders (`model/`, `repository/`, ...); a small feature is kept flat in a single package.

src/main/java/com/guardwork/backend/
├── BackendApplication.java        # Spring Boot entry point
├── common/                        # Shared response wrappers (ApiResponse, PageResponse)
├── logging/                       # Request correlation, summaries, and safe diagnostics
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
└── staff/                         # Staff governance & verification queue/decisions
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

## Shared registration and email verification

Shared registration creates an active unverified User and sends no mail. Users may
sign in and use Candidate capabilities immediately; they explicitly request and
confirm email proof before applicable new-User recruiting operations. See
[shared registration](../docs/backend/unified-registration.md) for endpoints,
capability fields, mail configuration, the consolidated account-email proof migration and coordinated rollout.
Account email verification uses a six-digit code entered as the string `emailSecret`, valid
for 10 minutes with at most five failed attempts and a 60-second resend cooldown.
Migration `V2026.10.30.85__account_email_verification_codes.sql` adds the persisted
attempt counter and invalidates outstanding account-verification links; request a new code
after deployment. Deploy the client code-entry form with this backend. Invitation links
and their delivery configuration are unchanged. Account code mail needs only
`ONBOARDING_EMAIL_FROM` and `ONBOARDING_EMAIL_CREDENTIAL`, without a frontend URL.
All Users follow the same recruiting email gate; stored verification flags are preserved. No phone proof
or account type is added to shared registration. Continue through
[complete Company setup](../docs/backend/company-setup.md) or
[verified-email invitation acceptance](../docs/backend/verified-email-invitations.md).
Separate employer signup, phone OTP, provisional saves and standalone evidence uploads
are [retired with HTTP 410](../docs/backend/employer-onboarding.md); retained records remain intact.
No frontend source is bundled in this checkout.

## API Docs

Swagger UI is exposed via [springdoc-openapi](https://springdoc.org/):

- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI spec: http://localhost:8080/v3/api-docs

Staff API `ResponseStatusException`s use the standard response envelope with the original HTTP
status and reason.

Company verification responses for Staff and Owner views expose `processingStatus` and
`reviewOutcome` alongside the existing `verificationStatus`. `PENDING` maps to processing
`PENDING` with a null outcome; `VERIFIED` and `REJECTED` map to processing `CHECKED` with the
corresponding outcome. Eligibility continues to use `verificationStatus`; `CHECKED` alone does
not mean approval. These are derived fields and require no schema migration.
See [complete Company setup](../docs/backend/company-setup.md) for atomic multipart dossiers,
private legal evidence, Staff review and rejected correction, and [retirement and decision delivery](../docs/backend/employer-onboarding.md)
for mail configuration, retained-data compatibility and recovery. The approved replacement is
recorded in [ADR 0008](../docs/architecture/decisions/0008-unified-registration-and-company-setup.md).

Company, Staff and known application-error advice use order `0`, ahead of the global
`InternalServerExceptionHandler` at `LOWEST_PRECEDENCE`. Spring selects the first applicable
advice with a matching handler; this ordering preserves existing status codes and response
envelopes before the catch-all maps unexpected exceptions to a generic HTTP 500.

See [private Job Posting drafts](../docs/backend/job-drafts.md) for the Company job APIs,
content bounds, eligibility, single migration, and notification compatibility.

## Database

- Schema managed via Flyway: `src/main/resources/db/migration` (`V{version}__{description}.sql`).
- Local Postgres via `docker-compose.yml`, connection settings in `application.properties`.
- Access-token lifetime is configurable with `GUARDWORK_JWT_ACCESS_EXPIRATION_MS` (milliseconds,
  default `900000`, or 15 minutes). Spring Boot imports an optional `.env` file from the backend
  working directory; an exported environment variable takes precedence over the file.

## Tooling

```bash
docker compose -f docker-compose.test.yml up -d --wait  # start test database once
./mvnw test            # run tests
./mvnw package         # build
```

After switching branches, use `./mvnw clean test` or `./mvnw clean package` so
removed test classes and migration resources cannot remain in `target/` and alter
verification. If an isolated test database already applied migrations from another
branch, use a separately named isolated test database via
`BACKEND_TEST_DATABASE_URL`; do not repair migration history or reset a shared
or development database to hide a build mismatch.

### Job publication and expiry

Platform Staff members decide pending submitted revisions with
`PUT /api/staff/job-revisions/{revisionId}/decision`, providing the current job and
revision versions, `outcome` (`APPROVED` or `REJECTED`), and a trimmed 10–1,000
character rejection reason when rejecting. Company membership does not restrict
platform review, including review of the Staff member's own Company. Decisions
preserve submitted content and record both job and platform audit facts. Approval
rechecks Company verification, suspension, current responsibility and the proposed
deadline before publishing. Review recipients use the existing durable outbox and
inbox retry mechanism.

`GET /api/jobs/{jobId}` is public, including for signed-in users still completing
onboarding. It returns only approved content and Company ID/name. Drafts and
suspended Companies return 404. Availability becomes false at the approved deadline,
including when scheduled reconciliation has not yet run; expired approved details
remain readable. This endpoint does not accept Applications.

Expiry reconciles under Company then job locks and records one `JOB_EXPIRED` fact.
The scheduled worker uses `jobs.expiry.poll-ms=60000` and
`jobs.expiry.batch-size=100` defaults (valid batch range 1–1000), committing each
job independently. Set `jobs.expiry.worker-enabled=false` to disable scheduling;
`JobExpiryService.expireDue()` is the bounded operational recovery entry point.
Tests disable scheduling and control the Clock explicitly. No schema migration or
new dependency accompanies moderation, public details, or expiry.


### Staff Job Posting hiding and history

UC-13/UC-14 add `PUT /api/staff/jobs/{jobId}/hide` and
`GET /api/staff/jobs/{jobId}/history` for active Platform Staff. A current job version
and a trimmed 1–1000 code-point reason are required to suspend a public posting.
The change and both audit records commit atomically; history is read-only and
paginated independently of revision history. Suspended content is unavailable on
the public job endpoint; Company authoring/lifecycle actions cannot restore it.
Pending review decisions preserve suspension.

Migration `V2026.10.31.86__staff_job_suspension.sql` adds `SUSPENDED` to the status
constraint. Deploy compatible clients and stop older writers; rollback requires
reviewed treatment of suspended rows. Public job details send `Cache-Control:
no-store`; existing responses cached before rollout may require invalidation.
See [job workflow documentation](../docs/backend/job-drafts.md#staff-hiding-and-posting-event-history-gw-93)
and the [approved contract](../docs/software-requirements/adm/staff-job-management-contract.md).
No new dependency, notification channel, public search or restoration workflow is added.


### GW-93 development Job Postings

The existing local seed now creates nine Vietnamese demo jobs under `GW-DEMO-LOCAL-001`.
They use synthetic content/history, Owner Responsibility and an assignment to the
existing active `demo_recruiter` membership when eligible. No notification or mail
is sent. These are development fixtures, not evidence of real review/delivery.

| Fixture marker | Initial Job Posting state | Manual check |
| --- | --- | --- |
| `GW93-HIDE-READY` | `PUBLISHED` | Public content is readable; Staff can hide it with current version and reason. |
| `GW93-PUBLISHED-EDIT` | `PUBLISHED` | A second revision is pending; pending content must not replace public approved content. |
| `GW93-SUSPENDED` | `SUSPENDED` | Public GET returns 404; Staff history retains actor and reason. |
| `GW93-SUSPENDED-EDIT` | `SUSPENDED` | A second revision is pending; approving it must keep the posting hidden. |
| `GW93-PAUSED` | `PAUSED` | Public details remain readable with applications unavailable; hide returns conflict. |
| `GW93-CLOSED` | `CLOSED` | Public details remain readable; hide/resume are unavailable. |
| `GW93-EXPIRED` | `EXPIRED` | Past approved deadline, no applications, system expiry actor is null. |
| `GW93-DRAFT` | `DRAFT` | Private incomplete draft; absent from submitted-revision Staff queues. |
| `GW93-PENDING` | `DRAFT` | First revision is `PENDING_REVIEW`; Staff can inspect/decide it; no public content yet. |

Start the backend to apply committed Flyway migrations, then run:

```bash
docker compose up -d seed
docker compose logs --tail 80 seed
```

The output lists each fixture marker, actual job/revision IDs, current states and
version tokens. IDs are allocated by PostgreSQL; do not assume a fixed ID.
Use `demo_staff` / `GuardWork123!` in the Staff portal, or `demo_owner` and
`demo_recruiter` with the same initial password in the public portal. Existing
passwords are preserved, so use changed credentials when applicable.

For Staff navigation, request `GET /api/staff/job-revisions?status=ALL`; the default
queue shows only pending revisions. Use the printed `job_id` for public details and
posting history, and reload a Staff revision for its current `jobVersion` before
hiding. See the [frontend integration guide](../docs/backend/staff-job-management-frontend-integration.md).

Reruns identify jobs by the stable `developmentFixture` marker in their immutable
`JOB_CREATED` facts, create only missing fixtures, and preserve all existing
content, versions, reviews, assignments and manual hide/pause/close actions. They
never restore a removed Recruiter membership or reset Company/account restrictions.
Fresh public deadlines are 30 days from the initial seed run; reruns do not extend
them. The expired scenario starts one day in the past. If a demo Owner/Staff/Company
is no longer eligible, creating missing jobs fails atomically with a clear error
rather than overriding its state. Duplicate or foreign fixture markers fail as
identifier collisions. No schema migration or dependency is added.
