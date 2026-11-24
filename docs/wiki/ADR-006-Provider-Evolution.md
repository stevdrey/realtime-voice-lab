# ADR-006 — Defer Live and library extraction
Status: Accepted (planning), 2026-11-23.

Context: Realtime is the course target; Live uses different conversation/delegation semantics. Alternatives: two-provider abstraction now, reusable JVM library now, Realtime-first. Decision: ship the verified Realtime lab first, then optional #21 spike with GPT-Live. Extract shared interfaces only after observing concrete common behavior. Consequence: fewer speculative abstractions; possible refactoring later.
