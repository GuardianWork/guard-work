# Proxy portal context and bound sessions

Status: User confirmed implementation contract on 2026-10-24.

Public and Staff Vite proxies preserve `/api` and overwrite `X-Guardian-Portal` with
their portal context. The backend binds login, signed access tokens and persisted
refresh sessions to `public`/`staff`, then enforces Platform roles independently.
Gateway deployments must overwrite incoming client header values and protect the
backend from routes bypassing that gateway. CORS is an optional exact-origin browser
policy; it is not proof of a trusted proxy or a grant of Staff authority.

Requests without the header keep existing Swagger/internal-client behavior using
authenticated role or stored refresh context. Legacy signed access tokens without
the claim use signed role context. This compatibility path is not strict frontend
origin attestation. Removing it would require a separately approved client migration.

Use an additive migration with role-based backfill rather than rewriting committed
schema history. Old refresh writers must be stopped before the new migration runs.
See the [portal contract](../../software-requirements/auth/portal-routing-contract.md)
for exact status codes, rollout and security boundaries. No dependency is added.
