# Pull request and CI workflow

This workflow applies to AI-assisted changes targeting `dev` or `main`. It requires
starting work from the latest `dev` and addressing CI failures before handing off a PR.

## Agent sequence

1. Read root `AGENTS.md` and applicable subsystem instructions. Inspect the current
   branch and working tree without changing them.
2. Before implementation, ask the user for approval to create or switch to a task
   branch based on `dev`; wait for the answer. Preserve existing changes and do not
   implement directly on `dev`.
3. After approval, fetch the latest `origin/dev`. Create a new task branch from it,
   or rebase the approved existing task branch onto it. Resolve any conflicts before
   implementation. If rebasing published commits would require rewriting remote
   history, get explicit approval before using `git push --force-with-lease`.
4. Implement the change, run applicable local checks, and fix local failures.
5. Obtain the explicit authorization required by `AGENTS.md` before pushing or
   creating a PR if the task has not already authorized those actions.
6. Push the task branch and open a normal PR targeting the agreed branch (`dev` for
   integration work, `main` when that is the intended target). The `/pr` skill and
   repository template are optional aids for writing a clear description.
7. Check CI for the PR's latest head commit. If any check fails, inspect its logs,
   fix the cause, run applicable local checks, and push the fix. If the rebase check
   fails because `dev` advanced, rebase on the latest `origin/dev` before updating
   the branch. Repeat until all required checks pass for the latest commit.
8. Report the PR URL, latest head SHA, check results, and any checks omitted. Do not
   merge the PR.

If the user authorizes only local work or GitHub write access is unavailable, finish
the local work and provide the branch/PR steps. Never claim CI passed without checking
GitHub results for the latest head commit.

## CI and merge protection

The existing [`ci.yml`](../../.github/workflows/ci.yml) runs backend tests, packaging,
and the rebase check on `feat/**` pushes and PRs targeting `dev` or `main`. The rebase
check requires the task branch to contain the latest `origin/dev` commit and rejects
merge commits after that base. CI runs when a PR is opened or updated; after fixing a
failure, wait for checks on the new head SHA.

For merge-time enforcement, configure branch protection or rulesets for both `dev`
and `main`:

- Require a pull request before merging and block direct/force pushes.
- Require the `Run Backend Tests, Package, and Upload Artifact` check.
- Require the `Rebase feature branch on dev` check.
- Require the branch to be up to date and PR conversations resolved.
- Limit bypass permissions to emergency maintainers.

GitHub checks can block merge when tests fail or the branch is behind `dev`; they run
after a push and do not prevent the push itself. Agent instructions require rebasing
before implementation, while this CI check catches a stale base before merge.

## GitHub settings

For each protected branch:

1. Open **Settings → Rules → Rulesets** (or **Settings → Branches** for classic
   protection) and create a ruleset matching `dev` or `main`.
2. Enable **Require a pull request before merging** and **Require status checks to
   pass**. Select `Run Backend Tests, Package, and Upload Artifact` and
   `Rebase feature branch on dev` after they have reported on a PR.
3. Enable **Require branches to be up to date** and **Require conversation resolution**.
4. Limit bypass permissions and block direct/force pushes to protected branches.

GitHub references: [Actions pull request events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)
and [protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches).
