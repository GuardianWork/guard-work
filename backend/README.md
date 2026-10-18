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

Company verification responses for Admin and Owner views expose `processingStatus` and
`reviewOutcome` alongside the existing `verificationStatus`. `PENDING` maps to processing
`PENDING` with a null outcome; `VERIFIED` and `REJECTED` map to processing `CHECKED` with the
corresponding outcome. Eligibility continues to use `verificationStatus`; `CHECKED` alone does
not mean approval. These are derived fields and require no schema migration.
See [employer onboarding](../docs/backend/employer-onboarding.md) for the new registration/verification
APIs, frontend, private legal dossiers, delivery configuration, migration, and remaining SMS and
production-readiness prerequisites. The technical choices were confirmed by the user and recorded
in [ADR 0005](../docs/architecture/decisions/0005-staged-employer-onboarding.md).

Company, Admin and known application-error advice use order `0`, ahead of the global
`InternalServerExceptionHandler` at `LOWEST_PRECEDENCE`. Spring selects the first applicable
advice with a matching handler; this ordering preserves existing status codes and response
envelopes before the catch-all maps unexpected exceptions to a generic HTTP 500.

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
