# AI contributor workflow

This repository uses one shared policy for AI coding tools. [Root `AGENTS.md`](../../AGENTS.md)
is the canonical project instruction file. Every agent working in this repository
must read it and the applicable subsystem instructions before editing. Do not create
provider-specific copies of this workflow; they can drift from the shared policy.

## Loading the shared instructions

At the start of each task, read root `AGENTS.md`. For backend changes, also read
`backend/AGENTS.md` and `backend/README.md`. Product behavior, API, schema, or
requirements changes also require `docs/agent-guides/requirements.md` and the
applicable approved specification.

If an agent does not load repository Markdown instructions automatically, the user
or agent operator must add these files to that agent's project context. No provider
is authorized to bypass the repository's approval boundaries.

## Workflow for every task

1. Read root `AGENTS.md`, inspect the current branch and working tree read-only, and
   preserve changes already present.
2. Before implementation, ask the user for explicit approval to create or switch to
   a task branch based on `dev`. Wait for the answer; do not edit implementation
   files or switch branches before approval. After approval, create a separate task
   branch based on `dev` and never implement directly on `dev`. If existing work
   could be affected, ask how to preserve it.
3. Read every applicable context pointer before editing. Backend tasks require
   `backend/AGENTS.md` and `backend/README.md`; product/API/schema changes also
   require `docs/agent-guides/requirements.md` and the applicable approved spec.
4. Inspect nearby code, tests, migrations, and docs. State the task scope and surface
   conflicts or unresolved decisions affecting product behavior, security, privacy,
   persistence, APIs, or architecture before implementation.
5. Make a narrow change. Obtain human approval at the boundaries listed in the root
   guide; do not interpret a tool's permission prompt as product or architecture approval.
6. Run checks required by the applicable subsystem guide and task. Report checks
   that were not run and why; do not claim unrun verification passed.
7. Report behavior changed, files/subsystems affected, checks and results, assumptions,
   risks/follow-ups, and user-visible/API/security/privacy/data implications.
8. For PR work, follow the [draft-first PR workflow](pull-request-workflow.md). Use
   `/pr` when available; otherwise use the tracked GitHub PR template.

## Safety and collaboration

- Use English for code, technical documentation, requirements, and Git history.
- Do not expose secrets, commit local environment files, or put private operational
  data in logs or documentation.
- Do not message external people or services, publish, deploy, commit, or push unless
  the user has explicitly authorized that action.
- Do not add dependencies or change schema, breaking contracts, architecture, or
  approved requirements without the approval described in `AGENTS.md`.
- Treat repository requirements and architecture documents as the source of truth;
  do not invent missing frontend, infrastructure, or authentication conventions.
- Keep provider settings, credentials, model preferences, and personal notes out of
  shared project files. Configure those locally in the provider's user-level settings.

## Local setup

No AI provider account, API key, model selection, MCP server, extension, or dependency
is required by this repository. Configure the chosen agent to load the shared
instructions, and confirm they are in context before making changes. Never commit
API keys or provider credentials.
