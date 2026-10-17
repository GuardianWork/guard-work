# Standalone Compose environment for backend tests

Status: Accepted within the local test setup requested by the user on 2026-10-17.

Use `backend/docker-compose.test.yml` to provision the existing PostgreSQL 18 test
boundary without repeating database credentials in shell commands. Keep this Compose
project separate from development, bind the test port only on loopback, and store
disposable data in tmpfs without attaching the development database volume.

The environment is shared by tests across all backend features. The Compose credentials
and test profile defaults agree, using `backend-test-only` as the disposable local
password. Setup is documented in [Backend tests](../../backend/testing.md).
Environment variables can override credentials; a port override requires a matching
test datasource URL.
The test-only database-name startup guard remains in place. No application runtime
architecture, dependency, API, or database schema changes are introduced.
