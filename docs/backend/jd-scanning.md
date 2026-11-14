# Local JD scanning (GW-119)

For real-mode startup with shared local settings, preflight checks, and Candidate
Copilot integration, see [local legal chat](legal-chat.md). Use the smoke command's
`--require-real` flag when verifying a model call rather than the stub workflow.

This opt-in mini workflow analyzes an exact saved Job Revision. PostgreSQL stores
the queue, immutable input/report, revision associations and attempt outcomes. The
local Python adapter supplies the analysis. Job submission and publication continue
to use normal human moderation regardless of scan availability.

Scope: [GW-SR-JD-SCAN-LOCAL](../software-requirements/rec/jd-scan-local-spec.md).
Decision: [ADR 0012](../architecture/decisions/0012-local-jd-scanning.md).

## Run locally without provider costs

No new dependencies are required. Use Java 25, the existing backend dependencies,
Python 3.10+ and the project's PostgreSQL 18 development service.

1. From the repository root, start the explicitly labeled stub adapter:

   ```bash
   python3 -m ai.jd_adapter --mode stub
   ```

2. In another terminal, start the normal local backend from `backend/`:

   ```bash
   docker compose up -d
   GUARDWORK_AI_ENABLED=true ./mvnw spring-boot:run
   ```

   Flyway applies `V2026.11.10.103__local_jd_scans.sql`. Wait for the normal local
   seed service to report completion (`docker compose logs -f seed`). Existing
   data and passwords are preserved. This migration adds three tables and does
   not change existing job/revision data. Do not delete development volumes.

3. From the repository root in a third terminal:

   ```bash
   python3 -m ai.smoke
   ```

   It logs in as `demo_owner` using the documented local password, creates one
   synthetic private draft, requests analysis, polls until completion, prints the
   report and checks result reuse. Every invocation creates a new draft; it never
   submits or publishes it. Stub output is `NEEDS_REVIEW` with a Vietnamese warning
   and no findings. This verifies plumbing, not legal detection.

   If the local account's password was changed, supply `GUARDWORK_SMOKE_PASSWORD`
   through your shell environment. `--username`, `--base-url`, `--jd` and
   `--wait-seconds` are optional. Credentials/tokens are never printed.

## Run real AI analysis with the sibling crawler

Requires the existing crawler virtualenv and dependencies, imported Neo4j graph,
fulltext index `legal_chunk_text`, and configured Gemini/NVIDIA provider credentials.
This workflow does not crawl, import, wipe or change the graph. Follow the sibling
project setup before running. Use synthetic JD content for initial provider testing.

Choose a snapshot identifier for the actual imported corpus. This is an operator
assertion, not an automatically verified graph checksum. Change it whenever the graph
changes. Change the analysis profile whenever the model, prompts, retrieval, bridge
or output semantics change; reusing a profile across such changes can reuse stale results.

From the repository root (adjust the snapshot to your dataset):

```bash
export GUARDWORK_AI_SHARED_KEY='local-development-only'
../crawler/.venv/bin/python -m ai.jd_adapter \
  --mode crawler --snapshot labor-local-2026-11-10 --profile jd-local-v1
```

Set `NEO4J_PASSWORD` in the process environment if the sibling configuration does
not already supply it. The adapter uses the crawler's environment loader for the
existing provider settings. It binds to `127.0.0.1:8011` and requires the shared key.

In the backend terminal, use matching configuration:

```bash
GUARDWORK_AI_ENABLED=true \
GUARDWORK_AI_MODE=crawler \
GUARDWORK_AI_LEGAL_SNAPSHOT=labor-local-2026-11-10 \
GUARDWORK_AI_PROFILE=jd-local-v1 \
GUARDWORK_AI_SHARED_KEY=local-development-only \
./mvnw spring-boot:run
```

Run the sample:

```bash
python3 -m ai.smoke --wait-seconds 720
```

Replace the example shared key in both processes for shared-machine use. Do not
publish the adapter port. This local architecture is not a production deployment.

Real analysis uses at most one detection and one verification call per attempt.
Article hints are supplemented by BM25 ranking within the imported Labor Code, using
adjacent Vietnamese syllable pairs. A bounded complete-article snapshot is loaded
from Neo4j at adapter startup; restart after corpus changes. The verifier may use
any source in the shared retrieved evidence;
every emitted quotation must still match that source exactly. This avoids losing
correct citations when a detector suggests the wrong existing article.
Verification is skipped for an empty, valid detection. Repeated requests reuse the
same scan within a Company for identical complete content/profile/corpus/mode and
Vietnam date. There is no semantic-similarity cache. Provider retries can cost more.
The existing provider wrapper does not expose token accounting, so `usage.available`
is false; it does not mean the call was free.

## API and result behavior

Use the normal Bearer token and existing response envelope:

```text
POST /api/companies/me/jobs/{jobId}/revisions/{revisionId}/scans
Body: {"expectedRevisionVersion":0}

GET /api/companies/me/jobs/{jobId}/revisions/{revisionId}/scans/{scanId}
GET /api/staff/job-revisions/{revisionId}/scans/{scanId}
```

POST returns HTTP 200 with the queued, running, completed or failed existing scan.
Store its `scanId` and poll GET. Missing/stale versions return 400/409; disabled
scanning returns 503. Reads enforce current Company membership/assignment; Staff
reads require current Staff authority and submitted content. Staff cannot open an
earlier scan containing private text removed before submission. No public scan API.

The response includes processing `status`, `mode`, `outdated`, `revisionVersion`,
`analysisText`, profile, corpus, analysis date, safe failure code and `report`.
Outdated is true when content, date or configured analysis identity changes; a
submission-only version change does not invalidate identical content.

Findings use Unicode **code-point** offsets into the returned immutable `analysisText`,
not offsets into the current editor text. In JavaScript use `Array.from(text)` to
slice those offsets. The text includes field labels and the complete saved content;
render everything as plain text. Do not render stale highlights on edited content.

Real reports distinguish potential risks, unresolved findings, no detected risks and
outside supported scope. The bridge retrieves full legal article text and validates
exact quotes/spans and complete model responses. It reuses the crawler's retrieval
and provider modules without changing the sibling repository. It does not reuse the
baseline parser's malformed-output-to-empty fallback or truncate legal evidence.

The current legal grounding scope remains the crawler's Labor Code 2019 baseline.
Article-level temporal applicability is not proven by its document status. The bridge
therefore labels these citations `UNVERIFIED` and routes associated findings to
`NEEDS_REVIEW`, even when a quotation is real. An empty detection is only “no risks
detected in this scope”, never a compliance certificate. Civil-service mode uses
the existing crawler scope gate. Human legal evaluation remains necessary.

## Queue recovery and operations

The scheduler polls every second and dispatches onto a dedicated single AI worker using
`FOR UPDATE SKIP LOCKED`. A 720-second lease exceeds the 660-second HTTP timeout.
The worker holds no transaction during HTTP/model calls and does not occupy the
scheduler used by unrelated expiry/delivery tasks. Expired workers cannot
complete/fail a replacement claim. Transient failures retry after 5/10 seconds,
with at most three claims. Invalid output fails immediately. A failed run remains
failed when reopened; changing input or the versioned profile creates a new run.
The mini workflow has no manual retry/admin API.

`jd_scan_attempts` records safe outcomes and duration. Provider requests can repeat
after an ambiguous timeout/crash; billing is not exactly-once. Snapshot/report
history is retained alongside existing job history. Production retention/disposal,
quotas, cost accounting, article-version resolution, reviewed model benchmarks,
file extraction and frontend presentation remain follow-up scope.

## Verification

```bash
# Repository root; Python standard-library contract tests.
python3 -m unittest discover -s ai/tests -v

# backend/; use the existing isolated PostgreSQL test service.
docker compose -f docker-compose.test.yml up -d --wait
./mvnw -Dtest=JdScanHttpTest test
./mvnw test
./mvnw -DskipTests package
```

Tests substitute the external model/HTTP/corpus boundaries only. A passing stub run
does not verify live provider credentials, corpus freshness or legal correctness.
