# Pull request size guard

| Changed files | Result |
| --- | --- |
| 0–30 | Pass |
| 31–40 | Pass with a scope warning |
| 41 or more | Fail. Split into independently reviewable PRs. |

Count tests, documentation, configuration and generated files along with application files. Keep required tests and security controls when splitting work.

## Local preflight

Use Git and Node.js 22 or newer. No npm install, credentials or service calls are needed for the script or tests.

```sh
git fetch origin main
node .agents/scripts/pr-size-preflight.mjs
node --test .agents/scripts/*.test.mjs
```

The command compares the branch, index and working tree to the merge base and counts distinct paths, including untracked, non-ignored files. A local count is an estimate and may include unpublished changes. A missing base, malformed hook payload or Git inspection error blocks with exit code 2. Refresh the base before retrying.

For a deliberately stacked PR, fetch its actual target and use `--base origin/<target>` for the manual command and `--base <target>` for `gh pr create`. The latter otherwise uses `branch.<head>.gh-merge-base` or defaults to `main`. For a push or manual check, the preflight also accepts `PR_PREFLIGHT_BASE` or `branch.<head>.prPreflightBase`. A PR's own published branch cannot serve as its stacked base.

Codex and Claude Code run the same script through project `PreToolUse` **Bash hooks**. Review and trust the checked-in hook before enabling it in your client. Claude Code v2.1.295 or later supports blocking launcher failures and timeouts via `onFailure`. The wrapper maps script launch failures to exit code 2 on both clients. Client configuration, hook trust and supported tool coverage determine whether the hook runs. Run the manual check for terminal or connector publication and when using shell aliases, wrapper scripts, a different checkout or an explicit push ref other than the current branch. Use the correct checkout and a direct publication command so the hook can infer its base.

A **Git hook** lives in Git's hook path and runs in a Git process. This project uses assistant hooks and has no Git hook installation step. A **GitHub repository webhook** delivers HTTP requests to a separately configured receiver. The GitHub Actions `pull_request` event starts a native workflow and needs no receiver, shared secret, or webhook configuration.

## GitHub Actions and protection for main

[Pull Request Size Guard](../../.github/workflows/pr-size-guard.yml) checks all PR base branches, including draft and documentation-only changes. The **PR file count** job reads GitHub's `changed_files` for the actual PR through a read-only token. It validates the PR head, target and count. It fails when the API fails and runs without checking out or executing PR source. A separate job runs the offline automation tests.

GitHub's count is the authoritative size result. Enforcement on `main` requires GitHub to make that result mandatory:

1. Land the workflow on `main` and let **PR file count** run successfully on a PR.
2. As a repository administrator, create an active branch ruleset for `main` under **Settings → Rules → Rulesets**, or configure equivalent classic branch protection. Require a pull request and require the **PR file count** status from **GitHub Actions**. Require an up-to-date branch so the check is refreshed against the current base. Keep direct, force-push and administrator bypasses closed.
3. Protect workflow and CODEOWNERS changes with a required [code-owner review](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners). The PR workflow definition comes from the proposed merge. A required status name and app alone cannot protect that definition or prohibit another workflow from using the same job name. Ensure there is an eligible, independent owner for sensitive changes. If you later adopt a merge queue, adapt and verify the required check for `merge_group` first.
4. Test with disposable PRs targeting `main` and synthetic changes. Confirm that 40 files pass with a warning and 41 fail with merging blocked. Confirm direct updates to `main` are rejected. A size failure governs merging after a PR opens.

On 2026-11-23 GitHub reported Actions enabled and `main` unprotected for this private repository. The ruleset and classic protection APIs returned HTTP 403 with a plan requirement. Until an eligible GitHub plan and an administrator enable the rules, maintainers must verify **PR file count** on the current revision before merging. See [GitHub ruleset availability](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/creating-rulesets-for-a-repository) and [required check behavior](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks).

## Transfer from Dokene

The shared agent instructions, scope policy, PR hooks, offline tests, PR authoring, issue loop, independent verification, review, risk analysis, operator verification and issue executive summary were adapted to the desktop lab. The executive summary is the workflow for communicating a finished PR's outcome on its issue.

The JavaFX skill replaces the Spring and React guidance. Exact-candidate checks and desktop journeys use the local repository and synthetic evidence. Dokene's backend webhook integration, server CI, wiki publisher, and issue-loop orchestration scripts depend on project-specific services, release mechanics or workflows outside this lab's scope.
