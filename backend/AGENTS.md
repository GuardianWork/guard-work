# Backend Agent Guide

This guide applies to every change under `backend/`.

## Architecture

Read [`README.md`](README.md) before editing. It is the source of truth for backend structure, layer responsibilities, persistence approach, migrations, and current commands. Inspect `pom.xml` and configuration directly for exact versions and dependencies.

Preserve the feature-first MVC structure:

- keep controllers as thin HTTP adapters;
- keep business behavior and validation in services;
- keep SQL in repository implementations using Spring JDBC; and
- use one top-level package per feature, splitting larger features by layer as documented.

Preserve scoped behavior when a shared backend contract is absent. A project-wide authentication, authorization, validation, pagination, error-envelope, or notification convention requires the approval defined by the root guide.

## Database

Treat committed Flyway migrations as immutable history. Evolve the schema with a new migration using the naming convention in `README.md`. Document migration and compatibility impact.

Use PostgreSQL-compatible behavior for persistence decisions. Keep credentials environment-backed and keep `.env.example` synchronized with required variables.

## Verification

Prove changed behavior at the narrowest useful boundary:

- unit tests for domain and service behavior;
- controller tests for validation, authorization, status codes, and response contracts;
- repository integration tests against PostgreSQL-compatible behavior; and
- end-to-end tests only for critical user journeys.

Add a regression test for every reproducible bug. Until a database-test strategy is approved, treat mocked JDBC tests as unit tests rather than integration tests and seek approval before adding test infrastructure.

Run the applicable commands documented in `README.md`. Run the test command for backend behavior changes and also the package command for dependency, configuration, build, or packaging changes. Manual verification supplements practical automated tests. Report every omitted check and why.

Use configured quality tools and preserve surrounding style. Introduce formatter, linter, static-analysis, or coverage gates only through an approved project decision.

Backend work is complete when the relevant test boundary proves the behavior, documented checks pass, migrations and configuration are synchronized, and any omitted verification is reported.
