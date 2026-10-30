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
3. Before each push, fetch and rebase on the latest `origin/dev`:

   ```bash
   git fetch origin dev
   git rebase origin/dev
   ```

   Resolve conflicts and rerun applicable checks before pushing. If this rewrites
   commits already published to the task branch, get explicit approval before using
   `git push --force-with-lease`.
4. Push the feature branch and open a **Draft PR** targeting the agreed base branch
   (`dev` for integration work, `main` only when that is the intended target).
   Never open it as ready for review.
5. Wait for CI on the draft. If any check fails, inspect its logs, fix the cause,
   push the fix to the same branch, and wait for checks on that new head commit.
   Repeat until every required check is green for the latest PR commit.
6. Invoke the `/pr` skill to write/update the description from the final branch diff.
   If the provider does not have that skill, follow
   [`.github/pull_request_template.md`](../../.github/pull_request_template.md)
   and preserve its Summary, Evidence, and Merge Danger sections.
7. Post a top-level PR conversation comment after CI is green. State the latest head
   SHA, the green required checks, local checks run, and any checks omitted. Do not
   paste a duplicate copy of the whole PR description. If the agent has no authorized
   GitHub commenting capability, provide the exact comment text to the user.
8. Confirm every required check is green for the latest PR head SHA. If a check is
   pending, missing, skipped unexpectedly, or failing, keep the PR in draft and
   investigate. After the final CI handoff comment is posted, mark the PR ready for
   review. Never merge it; report the ready PR URL and verification summary to the user.

When the user asks only for a local change and has not authorized remote actions,
finish the local work and provide the branch push / draft PR steps instead of
publishing it. Do not claim a PR is ready just because local tests pass.

## GitHub merge protection

Repository instructions guide every provider, while branch rules provide the
merge-time enforcement. Configure rulesets or branch protection for **both `dev` and
`main`** in GitHub repository settings:

- Require a pull request before merging; disallow direct pushes and force pushes.
- Require the `Run Backend Tests, Package, and Upload Artifact` check.
- Require the `Rebase feature branch on dev` check.
- Require the branch to be up to date before merging.
- Require all PR conversations to be resolved before merging.
- Do not allow bypass of these rules for routine merges.

The existing [`ci.yml`](../../.github/workflows/ci.yml) runs the backend test suite
and package job for PRs targeting `dev` or `main`. It also checks that the task
branch contains the latest `origin/dev` commit. GitHub Actions runs the default
`pull_request` activities (`opened`, `synchronize`, and `reopened`) for draft PRs,
so opening a draft starts CI; pushing a fix reruns it. A successful check must belong
to the latest commit before the protected branch can merge.

The rebase check runs on `feat/**` pushes and PRs; it fails if the task branch is
behind `dev` or contains merge commits after the `dev` base. GitHub evaluates this
after a push, so the agent instructions establish the pre-push sequence and the
required status check prevents a missed rebase from being merged.

GitHub's branch protection does not enforce that an agent used a particular slash
skill or prevent a user from converting a draft to ready early. The shared agent
instructions require the skill/template and require agents to mark the PR ready only
after all required checks pass for the latest head SHA. Required checks still prevent
merging while CI is failing or pending, including when the branch is behind `dev`.
Agents do not merge; review and merge remain human decisions.

## PR comment format

```markdown
Draft PR CI handoff

- Head: `<full commit SHA>`
- Required checks: all green on this head
- Local checks: `<commands and results>`
- Omitted checks: `<none, or checks and reason>`

All required checks passed for this head. The PR is marked ready for review; it has not been merged.
```

## GitHub settings walkthrough

For each protected branch:

1. Open **Settings → Rules → Rulesets** (or **Settings → Branches** for classic
   protection) and create a branch ruleset matching `dev` or `main`.
2. Enable **Require a pull request before merging** and **Require status checks to
   pass**. Select `Run Backend Tests, Package, and Upload Artifact` and
   `Rebase feature branch on dev` after they have reported on a PR.
3. Enable **Require branches to be up to date** and **Require conversation resolution**.
4. Keep bypass permissions limited to emergency maintainers; disable routine bypass.
5. Confirm direct pushes and force pushes to the protected branch are blocked.

GitHub references: [draft pull requests](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/proposing-changes-to-your-work-with-pull-requests/about-pull-requests),
[Actions pull request events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows),
and [protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches).
