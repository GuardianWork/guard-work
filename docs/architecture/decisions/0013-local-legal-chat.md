# Authenticated local legal chat

Date: 2026-11-14

Status: Local scope authorized by requesting user in conversation.

Extend the existing loopback Python adapter with `/chat` and expose it through
Spring's existing authenticated Candidate API. Keep USER role and current account
enforcement at the existing security boundary. Reuse adapter identity/configuration,
the crawler's provider selector and article retrieval. No new dependency or schema.

Use bounded article retrieval and one structured generation call; do not
silently start the crawler's separate Gemini-only, unauthenticated chat server.
Validate quotes against retrieved articles and disclose unresolved temporal validity.
Each question is independent. Browser conversation history is session-memory only.

The global fulltext index can rank unrelated legislation and broad question wording
above the relevant article. Cache up to 1000 complete Labor Code articles from Neo4j
at startup and use scoped BM25 with adjacent Vietnamese syllable pairs. Resolve
explicit article hints against that same snapshot and supplement them with ranked
articles. This adds no dependency, persistent index or embedding call. Restart and
change the corpus/profile identity after source/ranking changes.

Keep explicit stub mode for workflow tests, while chat requires real mode. Startup
checks report unavailable Neo4j/index/corpus/key; failures never become sample answers.
Use at most two simultaneous adapter operations and reject excess work with 429.
