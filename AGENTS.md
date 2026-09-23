# GuardWork Agent Guide

## Mission

GuardWork is a controlled Vietnamese job marketplace for Candidates, Recruiters/Organization Owners, and Platform Administrators. Build maintainable, requirement-faithful software that the three-person thesis team can explain and defend.

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

- **Requirements:** Read [`docs/agent-guides/requirements.md`](docs/agent-guides/requirements.md) before adding or changing a feature, user-visible behavior, business rule, API, schema, or software-requirements document.
- **Backend:** Read [`backend/AGENTS.md`](backend/AGENTS.md) before changing anything under `backend/`; it supplies the backend architecture, database, and verification rules.

## Workflow

### Before editing

1. Load every triggered context document above and the applicable subsystem documentation.
2. Inspect the nearby implementation, tests, and working tree. Identify overlap with teammate changes.
3. Define a task-focused scope and identify affected contracts, data, security boundaries, and documentation.
4. Resolve or surface ambiguity that affects user-visible behavior, authorization, privacy, persistence, API contracts, dependencies, or architecture. Make only local, reversible assumptions and disclose them.

Begin implementation only when the intended behavior and applicable constraints are unambiguous.

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

## Completion and handoff

A change is complete when its approved scope is implemented, applicable checks pass, authoritative documentation is synchronized, and no known requirement or architecture conflict remains hidden.

Report:

- changed behavior and the requirement or issue implemented;
- affected files or subsystems;
- checks run and results, plus checks omitted and why;
- assumptions, risks, follow-up work, and migrations; and
- user-visible, API, security, privacy, and data implications.
