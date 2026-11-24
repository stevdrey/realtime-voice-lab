# ADR-004 — Explicit safe local tools
Status: Accepted (planning), 2026-11-23.

Context: model calls are untrusted. Alternatives: reflection-based arbitrary actions, shell, MCP, explicit registry. Decision: allowlist toy workspace functions, validate arguments and call IDs, cap invocation count, keep UI changes on FX thread. Consequence: no arbitrary external capability and straightforward tests; adding a tool requires reviewed code. Prompts alone never grant authorization.
