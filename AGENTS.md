# Agent instructions

Realtime Voice Lab is a local Java 27 / JavaFX desktop lab in one Gradle module. Read [README.md](README.md), the [roadmap](docs/wiki/Roadmap.md), [contributing guide](docs/wiki/Contributing.md), and the ADRs relevant to the issue before working. Inspect the intended base, affected code and tests, and use the relevant [project skills](.agents/skills/README.md).

- Keep changes issue-scoped and independently testable. Aim for 30 changed files or fewer. The hard PR limit is 40, including tests, docs and configuration. [Run the preflight](docs/verification/pr-size-guard.md) before publishing and split larger work into coherent PRs.
- Keep experiments offline by default. Live Realtime calls or paid smoke tests require explicit permission and verified baseline limits. Use fake transport and devices with synthetic fixtures. Never log or commit secrets, private transcripts, raw audio, or unredacted payloads.
- Check GA event schemas against official docs when changing the protocol. Treat provider events, tool arguments and model output as untrusted input. Validate and authorize local side effects deterministically.
- Keep JavaFX mutations on the FX Application Thread. Own cancellation and cleanup for sockets, audio devices, workers and queued callbacks. Read [DESING.md](DESING.md) for UI or CSS work. Keep placeholders disabled or explicitly labeled as demos.
- Preserve the accepted single-module architecture. A server, database, SDK abstraction or provider migration requires a specifically approved issue.
- After the Gradle wrapper lands, verify Java 27 and use the wrapper for targeted and final offline checks. Report commands, outcomes and verification gaps accurately.
- For implementation, publication, review and issue summaries, follow the [agent workflow](.agents/README.md). Only publish comments or reviews when explicitly requested.

`.agents/` is canonical. Claude Code imports this file through `CLAUDE.md` and discovers the same skills through project links.
