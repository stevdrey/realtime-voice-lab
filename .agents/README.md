# Project agent workflow

1. Confirm the issue, intended base, acceptance criteria, and relevant ADRs. Follow the [skill index](skills/README.md) when the work has a matching workflow.
2. Estimate the entire PR, including tests, docs, and configuration. Keep intermediate PRs independently testable and preserve the security and privacy boundaries in [Security and Cost](../docs/wiki/Security-and-Cost.md).
3. Run focused offline checks while implementing. Review the final candidate, expand verification for affected boundaries, and record what was verified.
4. Run [PR preflight](../docs/verification/pr-size-guard.md) before publishing. Use the real issue reference and the PR template. Track follow-up separately from completed acceptance criteria.

Codex discovers skills under `.agents/skills/`. Claude Code discovers the same directories using relative links under `.claude/skills/`. If the checkout cannot materialize symbolic links, open the canonical skill from the index. Keep executable automation independent of the presence of either assistant.

For governance and preflight, the implementation scripts use Git and Node's standard library. Review and summary skills are instructions for an existing session. They set up no hosted review service, GitHub App, or separate model invocation.
