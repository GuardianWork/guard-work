# OpenTunnel AI launcher (GW-136)

This is an opt-in alternative to the existing ngrok launcher. Run it from the
repository root:

```bash
bash ai/run-local-opentunnel.sh
```

The launcher starts the existing local Neo4j service if needed, starts the real
adapter on `127.0.0.1:8011`, and uses the **OpenTunnel CLI at opentunnel.xyz** to
expose that port over HTTPS. It checks matching local and public `/health`
responses before printing `AI service READY`. Readiness does not call an AI model.
The ngrok launcher, source files, and existing environment files are unchanged.

## Prerequisites and configuration

- Install the OpenTunnel CLI separately using the project's
  [official installation instructions](https://github.com/anomalyco/opentunnel#installation).
  The launcher does not install dependencies. The similarly named VS Code
  extension using Cloudflare Quick Tunnels is a different product.
- Keep the existing sibling `../crawler/.venv`, provider credentials, imported
  Neo4j corpus and `legal_chunk_text` index configured. This launcher does not
  crawl, import or wipe data. Local Neo4j startup may prompt for sudo.
- Port 8011 must be free. Stop a running ngrok AI launcher before starting this
  version, because both adapters use that port.
- Use a real `GUARDWORK_AI_LEGAL_SNAPSHOT`, the matching
  `GUARDWORK_AI_PROFILE`, and a `GUARDWORK_AI_SHARED_KEY` of at least 16 characters.
  The adapter and backend must share these values. The OpenTunnel service is
  reserved to profile `guardwork-ai`; do not add unrelated routes to it.

Settings are read as data, in descending precedence: process environment,
`backend/.env`, optional ignored `ai/.env.opentunnel.local`, `ai/.env.local`, and
the sibling `crawler/.env`. No ngrok environment file is loaded. Configure keys
only in ignored local configuration or process environment; never commit them.

Check the real adapter prerequisites without opening a tunnel:

```bash
bash ai/run-local-opentunnel.sh --check
```

## Connect the backend

After readiness, copy the printed HTTPS origin into the backend's **process
environment**. In another terminal, replace the example with the printed URL:

```bash
cd backend
GUARDWORK_AI_ADAPTER_URL=https://route.tunnel.opentunnel.xyz ./mvnw spring-boot:run
```

This command overrides the old URL without editing `backend/.env`. Keep matching
AI mode (`crawler`), enabled flag (`true`), shared key, profile and legal snapshot
configured for the backend as described in [legal chat](legal-chat.md).
If those values are configured only in `ai/.env.opentunnel.local`, supply them to
the backend separately: the backend does not load that optional launcher file.
For Render or another hosted backend, set `GUARDWORK_AI_ADAPTER_URL` and the
matching AI settings in its deployment environment, then restart/redeploy.
Verify `GET /api/ai/health` through backend Swagger. A changed URL requires a new
backend environment value and restart/redeployment.

The launcher deliberately prints the URL instead of rewriting any existing
configuration. It never prints the shared key. Existing API contracts, caller
authentication, TLS verification and legal snapshot semantics are preserved.
There are no database migrations.

## Lifecycle and diagnostics

Keep the launcher terminal open. Ctrl+C or SIGTERM stops its adapter and runs
`opentunnel --profile guardwork-ai down`. The tunnel identity and route remain in
OpenTunnel's private user storage, allowing later runs to reuse their hostname.
The CLI may register its background service at login while running; `down`
removes that registration. The launcher refuses an already running profile or
unrelated routes and never stops another OpenTunnel profile. See the
[CLI lifecycle and storage reference](https://github.com/anomalyco/opentunnel/blob/dev/packages/cli/README.md).

First-time certificate provisioning can take several minutes; this launcher
allows five minutes and checks adapter liveness while waiting. Public readiness
then has a one-minute deadline. Later transient bridge disconnections use the
CLI's reconnect behavior. The launcher checks daemon liveness every five seconds;
it does not continuously check public reachability. Use the public `/health` or
backend readiness endpoint to check end-to-end availability.

Startup logs live in the printed private temporary directory (owner-only directory
access). OpenTunnel also keeps its own profile logs in user storage. Do not share
logs, identity files, private keys or local environment files. HTTPS is terminated
by the local OpenTunnel client; the adapter still enforces its shared key for
analysis/chat. `/health` is intentionally public and does not receive the key.
The launcher refuses redirects during health checks.

If startup fails, cleanup still runs. After a forced kill, machine shutdown or
cleanup failure, stop the reserved service explicitly before retrying:

```bash
opentunnel --profile guardwork-ai status
opentunnel --profile guardwork-ai down
```

The launcher leaves the shared Neo4j service running. It never kills a process
that already occupied port 8011, deletes a tunnel identity, or rewrites existing
project files. Hosted OpenTunnel rate and connection limits still apply; consult
the [server limits](https://github.com/anomalyco/opentunnel/blob/dev/docs/server.md#limits).
