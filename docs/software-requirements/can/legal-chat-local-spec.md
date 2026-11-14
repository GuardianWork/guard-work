# Local legal chat integration

| Field | Value |
| --- | --- |
| Specification ID | GW-SR-LEGAL-CHAT-LOCAL |
| Status | Draft; implementation scope authorized by requesting user in conversation |
| Owner / human approver | vy (requesting user) |
| Last updated | 2026-11-14 |

The user authorized connecting the existing Candidate Copilot to real analysis and
continuing on the current branch while preserving existing work. Formal approval
status remains a human action.

- POST `/api/candidates/me/legal-chat` accepts `{question}` using existing public
  portal authentication and active Platform USER authorization. Trim and require
  1–2000 Unicode code points. Do not send account/profile data to the provider.
- Use the existing loopback adapter and shared key. Chat requires enabled crawler
  mode; disabled/stub mode returns 503, never a sample answer. Invalid input returns
  400, provider/adapter failures 503, bounded adapter concurrency exhaustion 429.
- Return the existing ApiResponse envelope with schema version, mode, profile,
  corpus snapshot, Vietnam analysis date, model, answer, status, citations and
  warnings. Status is ANSWERED or INSUFFICIENT_CONTEXT. An answered response requires
  source identifiers and verbatim quotations validated against retrieved evidence.
  Unknown citations and fabricated quotations fail rather than appearing grounded.
- Reuse the crawler's Labor Code 2019 corpus/article identities and configured generation
  provider without changing the sibling repository or adding dependencies. Rank complete
  articles using scoped BM25 with Vietnamese syllable pairs, cached in process for the
  configured corpus snapshot (at most 1000 articles). Restart after corpus changes. Retrieve
  complete articles, bound evidence and output sizes, and make at most one generation
  call per question (provider-wrapper retries remain possible). No embedding calls.
  Corpus/snapshot identity must match the backend configuration. Applicability of
  individual article versions remains UNVERIFIED and must be disclosed.
- Browser calls only the authenticated backend. Existing suggested questions request
  real responses. Show pending, source, insufficient-context and recoverable error
  states; retain failed questions with an explicit retry action. Never display a
  locally generated answer as AI output. Prevent duplicate concurrent submissions.
- Conversations/drafts stay in React memory; no chat database tables, history API,
  cross-question context, token-cost accounting, automatic job decisions or new
  authentication architecture. State that each question must include its context.
  Do not log prompts, answers, credentials or raw provider errors.
- Keep scanning opt-in. Supply documented local real-mode startup/preflight commands;
  verify real chat and JD scan through the running frontend separately from tests
  that substitute the external model/HTTP boundary.

Verification covers authentication, input validation, mode/config mismatches,
provider/malformed-output failures, exact quote grounding, frontend async states,
and real browser requests/results. No source or model citation certifies current
legal applicability or replaces human legal review.
