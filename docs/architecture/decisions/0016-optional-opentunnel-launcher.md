# Optional OpenTunnel AI launcher

Date: 2026-11-28

Status: Authorized by vy in conversation for GW-136: use branch
`feat/GW-136-migrate-opentunnel`, create a new file version, and do not rewrite
current files.

## Decision

Add `ai/run-local-opentunnel.sh` and its standard-library Python service module as
an explicit alternative to the ngrok launcher. Use the OpenTunnel CLI hosted at
`opentunnel.xyz`, with a dedicated `guardwork-ai` profile. Provision a random route
to the loopback adapter and preserve caller authentication and HTTPS validation
under ADR 0015. Check local and public service identity before advertising readiness.

Leave all existing tracked files and local environment files unchanged. Report
the ready URL for the operator to apply to the backend process/deployment
environment. Add a separate setup guide rather than replacing the existing guide.
Do not install a CLI/package, change backend contracts, or modify the legal graph.

## Consequences

Operators can select either launcher explicitly. They cannot run both adapters on
port 8011 at once. The alternative requires a separately installed OpenTunnel CLI;
there is no added Python or backend package dependency. Its reserved profile must
be stopped and contain only this adapter's routes before launch. Cleanup stops
that profile's service, including its login registration, while retaining its
identity/hostname for reuse. Abrupt termination may require manual `down`.

The launcher prints the URL instead of automatically updating `backend/.env`.
Backend settings must still match the adapter key, mode, profile and legal
snapshot. A hosted backend requires an operator environment update/redeployment.
No database migration or requirement/API behavior change is introduced.
