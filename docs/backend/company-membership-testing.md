# Company Membership backend tests

The primary boundary is HTTP through the real Spring Security filter chain, authenticated registration/login, Flyway, and PostgreSQL. Email tests replace only `CompanyInvitationMailer`; provider contract tests use a local HTTP stub. Tests never send real invitation email.

The test classpath activates the `test` profile for ordinary Maven runs. Its datasource is environment-backed and defaults to an isolated `backend_test_db` on localhost port 55432. A test-only startup initializer refuses database names other than `backend_test_db` or its per-ticket suffixes before Flyway or the datasource initializes. The developer `backend_db` is never a test target. Production packaging does not include these test properties or the initializer.

## PostgreSQL 18 setup

Use the existing PostgreSQL 18 image with a separate temporary container and no developer data volume:

```bash
docker run --detach --rm --name guardwork-membership-tests \
  --publish 127.0.0.1:55433:5432 \
  --env POSTGRES_DB=backend_test_db \
  --env POSTGRES_USER=backend_test \
  --env POSTGRES_PASSWORD=membership-test-only \
  postgres:18-alpine
docker exec guardwork-membership-tests pg_isready -U backend_test -d backend_test_db
```

After readiness succeeds, run from `backend/`:

```bash
export BACKEND_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55433/backend_test_db
export BACKEND_TEST_DATABASE_USER=backend_test
export BACKEND_TEST_DATABASE_PASSWORD=membership-test-only
./mvnw test
./mvnw -DskipTests package
```

The password above is for this temporary loopback-only test database. Supply separate environment credentials for production. Unit tests can run individually without PostgreSQL; real HTTP and migration tests require the isolated database.

Each concurrently implemented ticket uses its own isolated database. New migrations are immutable after commit. When combining migration sequences developed independently, recreate only the temporary test container/database instead of changing checksums or enabling out-of-order production migrations.

After verification:

```bash
docker stop guardwork-membership-tests
```

The final integrated checks use PostgreSQL 18. The initial ownership/concurrency development loop also ran on the locally available PostgreSQL 17.11 before the PostgreSQL 18 container was available.
