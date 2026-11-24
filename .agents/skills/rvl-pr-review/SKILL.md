---
name: rvl-pr-review
description: Review a Realtime Voice Lab GitHub PR for actionable regressions, security boundaries, JavaFX concurrency and verification gaps.
---

# Realtime Voice Lab PR review

Pin the PR base and head and inspect the full diff, linked issue, and applicable repository instructions. Confirm the current check results and their SHA. Record unreviewed material.

Validate candidate findings against the changed code and callers. Check issue scope, Java 27 and Gradle toolchain, FX thread ownership, stale callbacks, resource lifetime, untrusted events and tool arguments, redaction, bounded spend and offline test coverage as applicable. For workflow changes, check event trust, token permissions, pinned actions, and job name integrity. Use deterministic source inspection and synthetic tests.

Report only actionable regressions introduced by the changes, with severity, a precise location, and a concrete impact. Separate verified findings from coverage limitations. On re-review, check fixes and new changes against the new head. Report in chat by default. Publish a review or comment only on explicit request.
