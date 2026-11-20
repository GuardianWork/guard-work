# Configurable local and hosted AI service endpoints

Date: 2026-11-16

Status: Authorized by vy in conversation: configure the backend AI link in an env
file in the backend folder and support services running locally or elsewhere.

## Decision

Extend the loopback-only outbound transport restrictions in ADRs 0012 and 0013
to operator-controlled HTTP(S) service base URLs. Preserve loopback defaults;
support Docker/private DNS, hosted HTTPS services and optional reverse-proxy path
prefixes. Use HTTPS on public networks; HTTP is for trusted local/private networks.
Reject non-loopback HTTP by default. A private HTTP deployment must explicitly set
`GUARDWORK_AI_ALLOW_INSECURE_HTTP=true`; this applies to both clients without
inferring public/private reachability from DNS names or relaxing TLS verification.
TLS certificate/hostname validation remains the JDK default. Reject malformed or
relative URLs, unsupported schemes, URL credentials, query strings, fragments and
invalid ports. Never follow redirects or accept a destination from request/model
arguments. A small AI-specific URL utility keeps the three clients consistent.

Import shared `ai/.env.local` defaults first, then `backend/.env`, so the backend's
own connection configuration wins. Process/Render environment values retain the
highest precedence. `GUARDWORK_AI_ADAPTER_URL` selects the health/JD/legal-chat
base URL.

The endpoint must implement GuardWork's existing adapter API. Hosting flexibility
does not imply compatibility with arbitrary provider request/response formats.
Backend results retain their schema, identity and grounding validation. No new
dependency, database migration or callback route is added.

## Consequences

Render can call an existing local adapter through an explicitly configured HTTPS
gateway while Neo4j/provider credentials remain on the AI host. The backend stores
only its caller key and matching service identity in ignored local configuration
or deployment secrets. Gateway operators can see traffic where TLS terminates;
ngrok URL changes require updating deployment environment and restarting/redeploying.

Operators must select trusted destinations and protect caller credentials. A remote
service remains an untrusted response boundary. Synthetic HTTPS tests verify all
both clients, path prefixes, authentication, redirect refusal and TLS validation;
configuration-loader tests verify backend-file and process-environment precedence.
