# Backend tests

This setup is shared by all backend features. Run commands from `backend/`.

The test classpath activates the `test` profile for ordinary Maven runs. Its datasource is environment-backed and defaults to an isolated `backend_test_db` on localhost port 55432. A test-only startup initializer refuses database names other than `backend_test_db` or its per-ticket suffixes before Flyway or the datasource initializes. The developer `backend_db` is never a test target. Production packaging does not include these test properties or the initializer.

## PostgreSQL 18 setup

From `backend/`, start the isolated PostgreSQL 18 service and wait for readiness:

```bash
docker compose -f docker-compose.test.yml up -d --wait
./mvnw test
./mvnw package
```

Keep the service running while developing; subsequent test runs need only `./mvnw test`.
No application server needs to be started separately. Unit tests can run individually without
PostgreSQL; real HTTP and migration tests require this database.

The standalone Compose project `guardwork-tests` binds only `127.0.0.1:55432` and creates
`backend_test_db` with User `backend_test` and the test-only password `backend-test-only`.
These match the test profile defaults, so no environment exports are needed. The password is
only for disposable local tests; production uses separate credentials. Compose supports
`BACKEND_TEST_DATABASE_USER` and `BACKEND_TEST_DATABASE_PASSWORD` overrides; export the same
values when invoking Maven. An exported `BACKEND_TEST_DATABASE_URL` must point to this test
service or another isolated test database. The development Compose file and its volume are
not used. Run this file standalone, rather than merging it with `docker-compose.yml`.

Test storage uses tmpfs and is disposable: stopping or removing the container loses its data.
To discard the test environment after verification:

```bash
docker compose -f docker-compose.test.yml down
```

Each concurrently implemented ticket uses its own isolated database. New migrations are
immutable after commit. When combining migration sequences developed independently, recreate
only the temporary test service with `down` followed by `up -d --wait` instead of changing
checksums or enabling out-of-order production migrations. Separate concurrent Compose instances
must use different project names, host ports and matching test datasource URLs.

For example, if the default port is already in use, run a separate instance:

```bash
BACKEND_TEST_DATABASE_PORT=55435 docker compose -p guardwork-tests-local -f docker-compose.test.yml up -d --wait
BACKEND_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55435/backend_test_db ./mvnw test
docker compose -p guardwork-tests-local -f docker-compose.test.yml down
```

## Feature coverage

See [Company Membership tests](company-membership-testing.md) for its HTTP, security, migration and mail test boundaries.

Portal authentication uses the same isolated PostgreSQL boundary, with no new test
infrastructure. `PortalAuthHttpTest` covers login/refresh/logout for both portals,
wrong-portal credentials with and without Bearer, non-consuming refresh rejection,
Staff role enforcement, malformed portal headers, and opt-in direct-origin CORS.
`ViteProxyAuthHttpTest` covers preserved browser Origin with a rewritten Host while
direct CORS is disabled. `PortalSessionMigrationTest` proves existing-session backfill
and database constraints using transaction-local temporary tables.

```bash
./mvnw -Dtest=PortalAuthHttpTest,ViteProxyAuthHttpTest,PortalSessionMigrationTest test
```

These are backend HTTP/security-chain tests, not a browser running either external
Vite project. Gateway header rewriting and the actual frontend bundle require
deployment/frontend verification outside this repository.
