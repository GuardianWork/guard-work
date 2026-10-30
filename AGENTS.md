# GuardWork Agent Guide

## Mission

GuardWork is a controlled Vietnamese job marketplace for Candidates, Recruiters/Organization Owners, and Platform Staff members. Build maintainable, requirement-faithful software that the three-person thesis team can explain and defend.

Use English for code, technical documentation, requirements, and Git history. Preserve unrelated and concurrent teammate work.

## Sources of truth

Apply guidance in this order:

1. The user's current explicit instruction.
2. The applicable approved software requirement.
3. Architecture and project documentation.
4. Tests that express intended behavior.
5. The existing implementation.

Surface conflicts. A user instruction does not silently rewrite an approved requirement; identify the conflict and obtain the required approval.

## Context pointers

- **AI workflow:** [`docs/agent-guides/ai-workflow.md`](docs/agent-guides/ai-workflow.md) is the provider-neutral workflow for every AI agent. This `AGENTS.md` is the canonical project policy.
- **Pull requests and CI:** Follow [`docs/agent-guides/pull-request-workflow.md`](docs/agent-guides/pull-request-workflow.md) for normal PR creation and CI failure handling.
- **Requirements:** Read [`docs/agent-guides/requirements.md`](docs/agent-guides/requirements.md) before adding or changing a feature, user-visible behavior, business rule, API, schema, or software-requirements document.
- **Backend:** Read [`backend/AGENTS.md`](backend/AGENTS.md) before changing anything under `backend/`; it supplies the backend architecture, database, and verification rules.

## Workflow

### Before editing

1. Load every triggered context document above and the applicable subsystem documentation.
2. Inspect the current branch and working tree without changing them. Before any implementation, ask the user for explicit permission to create or switch to a task branch based on `dev`; wait for their answer. Do not edit implementation files or change branches before approval.
3. After approval, preserve existing work, fetch the latest `origin/dev`, and create a separate task branch from it or rebase the approved task branch onto it before implementation. Never implement directly on `dev`. If uncommitted work would be affected, stop and ask how to preserve it.
4. Inspect the nearby implementation, tests, and working tree. Identify overlap with teammate changes.
5. Define a task-focused scope and identify affected contracts, data, security boundaries, and documentation.
6. Resolve or surface ambiguity that affects user-visible behavior, authorization, privacy, persistence, API contracts, dependencies, or architecture. Make only local, reversible assumptions and disclose them.

Begin implementation only after the branch approval above and when the intended behavior and applicable constraints are unambiguous.

Use this required question before implementation:

> May I create or switch to a task branch based on `dev` and rebase it onto the latest `origin/dev` before implementing? I am currently on `<branch>` and the working tree is `<clean/has changes>`.

### While editing

- Keep the change narrow. Include a small adjacent refactor only when it reduces implementation risk and can be verified with the task.
- Update authoritative documentation with changes to behavior, setup, commands, configuration, contracts, migrations, or architecture.
- Comment rationale and non-obvious constraints rather than narrating the code.
- Change generated artifacts through their source and documented generator. Keep build output, IDE state, local secrets, and machine-specific files untracked.

### Approval boundaries

Obtain explicit human approval before:

- adding, removing, or upgrading a dependency;
- changing a database schema or introducing a breaking API change;
- changing an approved requirement or cross-project technical contract;
- selecting or scaffolding frontend, infrastructure, authentication, authorization, validation, pagination, error-envelope, or notification architecture;
- performing a broad refactor or destructive operation; or
- committing or pushing Git changes.

Agents may choose reversible implementation details and make small verified refactors inside the approved scope. After dependency approval, use the subsystem package manager, justify the choice, update build metadata, and verify tests and packaging. Record approved, consequential, hard-to-reverse choices as lightweight ADRs under `docs/architecture/decisions/`, creating the directory when the first ADR is needed.

For PR work, the user must explicitly authorize remote push/PR actions unless that authorization is already present in the task. Create a normal PR when authorized. Check CI on the latest head commit and fix failures before handing the PR back. If CI reports the task branch behind `dev`, rebase on the latest `origin/dev` before pushing the fix. Get explicit approval before using `--force-with-lease` to rewrite published history. Do not merge the PR. The `/pr` skill and PR template may be used to prepare a clear description, but are not workflow gates.

## Completion and handoff

A change is complete when its approved scope is implemented, applicable checks pass, authoritative documentation is synchronized, and no known requirement or architecture conflict remains hidden.

Report:

- changed behavior and the requirement or issue implemented;
- affected files or subsystems;
- checks run and results, plus checks omitted and why;
- assumptions, risks, follow-up work, and migrations; and
- user-visible, API, security, privacy, and data implications.
