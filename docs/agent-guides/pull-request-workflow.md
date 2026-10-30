# Draft-first pull request workflow

This workflow applies to every AI-assisted PR targeting `dev` or `main`. It separates
automated validation from the user's decision to request review and merge.

## Agent sequence

1. Before implementing, ask the user for explicit approval to create or switch to a
   task branch based on `dev`; wait for approval. Preserve any current work and never
   implement directly on `dev`. Then read root `AGENTS.md`, applicable subsystem
   instructions, inspect the full diff, and run local checks required by the subsystem
   guide. Fix local failures before asking to publish.
2. Obtain the explicit user authorization required by `AGENTS.md` for remote pushes
   and PR creation if it is not already part of the task.
3. Push the feature branch and open a **Draft PR** targeting the agreed base branch
   (`dev` for integration work, `main` only when that is the intended target).
   Never open it as ready for review.
4. Wait for CI on the draft. If any check fails, inspect its logs, fix the cause,
   push the fix to the same branch, and wait for checks on that new head commit.
   Repeat until every required check is green for the latest PR commit.
5. Invoke the `/pr` skill to write/update the description from the final branch diff.
   If the provider does not have that skill, follow
   [`.github/pull_request_template.md`](../../.github/pull_request_template.md)
   and preserve its Summary, Evidence, and Merge Danger sections.
6. Post a top-level PR conversation comment after CI is green. State the latest head
   SHA, the green required checks, local checks run, and any checks omitted. Do not
   paste a duplicate copy of the whole PR description. If the agent has no authorized
   GitHub commenting capability, provide the exact comment text to the user.
7. Hand the Draft PR URL and verification summary to the user. Keep it in draft;
   only the user may mark it ready for review and merge it.

When the user asks only for a local change and has not authorized remote actions,
finish the local work and provide the branch push / draft PR steps instead of
publishing it. Do not claim a PR is ready just because local tests pass.

## GitHub merge protection

Repository instructions guide every provider, while branch rules provide the
merge-time enforcement. Configure rulesets or branch protection for **both `dev` and
`main`** in GitHub repository settings:

- Require a pull request before merging; disallow direct pushes and force pushes.
- Require the `Run Backend Tests, Package, and Upload Artifact` check.
- Require the branch to be up to date before merging.
- Require all PR conversations to be resolved before merging.
- Do not allow bypass of these rules for routine merges.

The existing [`ci.yml`](../../.github/workflows/ci.yml) runs the backend test suite
and package job for PRs targeting `dev` or `main`. GitHub Actions runs the default
`pull_request` activities (`opened`, `synchronize`, and `reopened`) for draft PRs,
so opening a draft starts CI; pushing a fix reruns it. A successful check must belong
to the latest commit before the protected branch can merge.

GitHub's branch protection does not enforce that an agent used a particular slash
skill or prevent a user from converting a draft to ready early. The shared agent
instructions require the skill/template and keep agents from making that transition;
required checks still prevent merging while CI is failing or pending. The human
reviewer is the final ready-for-review gate.

## PR comment format

```markdown
Draft PR CI handoff

- Head: `<full commit SHA>`
- Required checks: all green on this head
- Local checks: `<commands and results>`
- Omitted checks: `<none, or checks and reason>`

Ready for you to mark this PR as ready for review when you have reviewed the diff.
```

## GitHub settings walkthrough

For each protected branch:

1. Open **Settings → Rules → Rulesets** (or **Settings → Branches** for classic
   protection) and create a branch ruleset matching `dev` or `main`.
2. Enable **Require a pull request before merging** and **Require status checks to
   pass**. Select the CI check after it has reported at least once on a PR.
3. Enable **Require branches to be up to date** and **Require conversation resolution**.
4. Keep bypass permissions limited to emergency maintainers; disable routine bypass.
5. Confirm direct pushes and force pushes to the protected branch are blocked.

GitHub references: [draft pull requests](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/proposing-changes-to-your-work-with-pull-requests/about-pull-requests),
[Actions pull request events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows),
and [protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches).
