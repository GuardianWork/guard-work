# Local legal chat

POST `/api/candidates/me/legal-chat` accepts `{"question":"..."}` in the existing
`{status,message,data}` envelope. It requires an authenticated public-portal USER;
Staff, revoked and deactivated sessions cannot call it. Questions are independent,
trimmed and limited to 2000 Unicode code points. No account/profile fields or chat
history go to the provider. Chat is not persisted; browser threads clear on reload.

`data` includes `schemaVersion="1"`, `mode="crawler"`, `profile`, `legalSnapshot`,
`asOfDate` (Vietnam date), `model`, `status=ANSWERED|INSUFFICIENT_CONTEXT`, `answer`,
`citations`, `warnings`, and `usage.available`. Citations contain `nodeId`, `label`,
`documentNumber`, exact `quote`, `quoteGrounded=true`, `effectiveStatus` and
`applicability=UNVERIFIED`. The adapter validates quotations against complete
retrieved articles. This does not prove answer entailment or present applicability.
Render all source/model text as text.

Retrieval uses a bounded in-process snapshot of complete Labor Code articles from
Neo4j and BM25 with adjacent Vietnamese syllable pairs. This avoids the global
fulltext index's cross-corpus cutoff/ranking problem without embedding API calls.
Restart the adapter and change corpus/profile identity after source/ranking changes.

Suggested questions also use real requests. The frontend shows pending/recoverable
errors, keeps failed questions for explicit retry, suppresses duplicate submissions
and aborts abandoned requests on unmount. Responses stay with their original browser
thread when switching. Include full context in each question. No sample fallback.

Errors: 400 invalid input; 401 session; 403 Staff; 429 adapter busy; 503 disabled/stub
configuration, invalid response or provider unavailable. At most two adapter
operations run concurrently. Cancellation does not guarantee cancellation of an
already-started provider call; wrapper retries can incur additional cost. Ordinary
marketplace operations do not depend on chat availability.

## Start and verify

1. Start the existing development PostgreSQL and Neo4j services. On this machine,
   Neo4j uses `sudo systemctl start neo4j`. Preserve existing data.
2. Verify working provider credentials/model in the crawler environment, imported
   Labor Code 2019 and ONLINE `legal_chunk_text` index. A catalogue entry/key being
   present does not prove the account can invoke a model. Retired/denied models need
   an explicit local configuration change.
3. Copy `ai/.env.example` to ignored `ai/.env.local`; set the actual corpus identifier.
   Optionally select `LLM_BACKEND=gemini` or `nvidia`. Change the analysis profile after
   a provider/model change to prevent reusing earlier results.
4. From the repository root run `bash ai/run-local.sh check`. It checks dependencies,
   key presence, Neo4j and corpus/index without making a model call.
5. Stop old adapter/backend instances, then in separate terminals run
   `bash ai/run-local.sh adapter` and `bash ai/run-local.sh backend`. Both load the same
   local settings and explicitly select real mode. Ports are never killed automatically.
   A normal backend start from `backend/` also imports `../ai/.env.local`, so configured
   real mode survives restarting with `./mvnw spring-boot:run`. Explicit process
   environment variables still take precedence over the local file.
   GET `http://127.0.0.1:8011/health` should report `mode=crawler` and matching profile
   and snapshot. This is a startup/config check, not ongoing provider availability.
6. Run `python3 -m ai.smoke --require-real --chat --wait-seconds 720`. It creates one
   synthetic private draft, verifies real JD completion/reuse and requires a real
   grounded chat answer. It never submits/publishes. The old default smoke still
   intentionally supports stub tests; `--require-real` prevents confusing those with
   model verification.
7. Test `/can/copilot` as a Candidate: POST, response, source quotation and recoverable
   outage. Test `/rec/jobs` as an owner: synthetic draft, queued → completed, real mode,
   citations and repeat reuse. A no-evidence abstention alone does not prove a model
   call; use a question whose source exists in the imported corpus.

Credentials, local settings and screenshots remain untracked. No dependency or
database migration is required.

## Local verification — 2026-11-14

Real Playwright MCP checks passed through the public frontend proxy with Gemini,
the imported Neo4j corpus and a synthetic private draft. A custom question without
an article number returned ANSWERED and an exact grounded Article 17 quotation.
The JD button produced QUEUED → RUNNING → COMPLETED, two findings and grounded
Article 17 quotations for both; the final run took about four seconds. Earlier
workflow checks also covered report reuse, reload persistence, snapshot highlights,
and explicit retry after a busy response. Source applicability remains UNVERIFIED.
No job was submitted or published.

Checks: full backend suite (551 tests), focused chat/scan suites after the local
configuration change (14 tests), frontend services (126 tests), frontend async UI
regressions (3 tests), and Python adapter/retrieval regressions (16 tests including
two read-only tests against the real Neo4j corpus). Backend packaging, frontend build,
TypeScript, focused lint and diff checks passed. Provider results are fixture-level
verification, not a comprehensive legal-answer quality evaluation or load test.
