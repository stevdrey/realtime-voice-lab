---
name: create-pull-request
description: Create, draft or update a Realtime Voice Lab pull request with its issue link, exact verification evidence and change scope.
---

# Create pull request

Confirm the intended GitHub repository, base and head, linked issue and scope. Read the actual diff and apply [the size preflight](../../../docs/verification/pr-size-guard.md). Review verification results for the revision being pushed.

Use `[Issue-N] <imperative summary>` when a primary GitHub issue exists. Include its `RVL-NNN` identifier when useful. For approved maintenance without an issue, describe the work and state the absence of an issue in the body. Use [the PR template](../../../.github/pull_request_template.md) to document the outcome, motivation, changes, executed checks, risks and review focus. Use `Closes #N` only when the entire issue is delivered. Use `Refs #N` for partial or related work.

Prefer the connected GitHub tool or `gh pr create --base <branch> --body-file <file>`. Publishing via a connector still requires manual preflight. After publishing, report the PR and any verification gap. Preserve discussion and the intent of issue links on updates.
