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

```
src/main/java/com/guardwork/backend/
├── BackendApplication.java        # Spring Boot entry point
├── user/                          # User entity + persistence (shared)
│   ├── model/
│   │   └── User.java
│   └── repository/
│       ├── UserRepository.java        # interface
│       └── JdbcUserRepository.java    # JdbcTemplate impl
└── auth/                          # auth feature (flat — few files)
    ├── AuthController.java
    ├── AuthService.java
    ├── RegisterUserRequest.java
    ├── LoginRequest.java
    └── AuthResponse.java
```

### Layer responsibilities (MVC)

| Layer        | Responsibility                                                     |
| ------------- | ------------------------------------------------------------------- |
| `model`       | Entity POJOs (e.g. `User`)                                          |
| `repository`  | Data access — interface + `JdbcTemplate` (Spring JDBC) implementation |
| `service`     | Business logic and validation (e.g. `AuthService`)                  |
| `controller`  | Thin HTTP layer, maps DTOs ↔ service                                |
| `dto`         | Request/response objects (flat in the feature package)              |

### Conventions

- One feature = one top-level package (`user`, `auth`, ...).
- Business logic lives in `service`, never in `controller`.
- `service` depends on `repository`/`model` only, no direct SQL.
- DB access via `JdbcTemplate` (no JPA).

## API Docs

Swagger UI is exposed via [springdoc-openapi](https://springdoc.org/):

- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI spec: http://localhost:8080/v3/api-docs

## Database

- Schema managed via Flyway: `src/main/resources/db/migration` (`V{version}__{description}.sql`).
- Local Postgres via `docker-compose.yml`, connection settings in `application.properties`.

## Tooling

```bash
./mvnw test            # run tests
./mvnw package         # build
```