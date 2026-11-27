# Roadmap

Planning baseline: 2026-11-23. GitHub Issues hold acceptance criteria, implementation progress and verification. IDs are stable; issue links below are canonical.

| Phase | Epic | Work |
|---|---|---|
| A — Foundation | [#1](https://github.com/stevdrey/realtime-voice-lab/issues/1) | [RVL-001 #7](https://github.com/stevdrey/realtime-voice-lab/issues/7), [RVL-002 #8](https://github.com/stevdrey/realtime-voice-lab/issues/8), [RVL-003 #9](https://github.com/stevdrey/realtime-voice-lab/issues/9) |
| B — Audio | [#2](https://github.com/stevdrey/realtime-voice-lab/issues/2) | [RVL-004 #10](https://github.com/stevdrey/realtime-voice-lab/issues/10), [RVL-005 #11](https://github.com/stevdrey/realtime-voice-lab/issues/11) |
| C — Inspection | [#3](https://github.com/stevdrey/realtime-voice-lab/issues/3) | [RVL-006 #12](https://github.com/stevdrey/realtime-voice-lab/issues/12), [RVL-007 #13](https://github.com/stevdrey/realtime-voice-lab/issues/13), [RVL-008 #14](https://github.com/stevdrey/realtime-voice-lab/issues/14) |
| D — Tools | [#4](https://github.com/stevdrey/realtime-voice-lab/issues/4) | [RVL-009 #15](https://github.com/stevdrey/realtime-voice-lab/issues/15), [RVL-010 #16](https://github.com/stevdrey/realtime-voice-lab/issues/16) |
| E — Safety and release | [#5](https://github.com/stevdrey/realtime-voice-lab/issues/5) | [RVL-011 #17](https://github.com/stevdrey/realtime-voice-lab/issues/17), [RVL-012 #18](https://github.com/stevdrey/realtime-voice-lab/issues/18), [RVL-013 #19](https://github.com/stevdrey/realtime-voice-lab/issues/19), [RVL-014 #20](https://github.com/stevdrey/realtime-voice-lab/issues/20) |
| Optional | [#6](https://github.com/stevdrey/realtime-voice-lab/issues/6) | [RVL-015 #21](https://github.com/stevdrey/realtime-voice-lab/issues/21) |

## Recommended implementation order
1. #7 → #8 → #17 (baseline limits) → #9. Do not run paid sessions before #17 baseline is in place.
2. #10 → #11. #12 can be implemented immediately after #8.
3. #13 → #14. #15 can start after #9; integrate tool limits with #17; #16 follows #11 and #15.
4. #18 after #9/#17; #19 after #13/#15/#17.
5. #20 after all MVP issues; #21 only after #20.

## Release checkpoints
- v0.1: offline UI + text vertical slice, explicit session limits.
- v0.2: push-to-talk, streamed audio and transcripts.
- v0.3: inspector, VAD, interruption and toy tools.
- v1.0 learning MVP: all 14 mandatory issues and verification; not a production-readiness claim.
- Post-MVP: optional Live comparison; decide whether to extract shared code based on evidence.


## Post-MVP research direction — Adaptive AI Applications (2026-11-27)

The original MVP and optional GPT-Live exploration remain unchanged. After MVP verification (#20), the lab expands into local adaptive applications research: **Adaptive UI**, **specialized voice roles**, **hybrid semantic memory**, and **context/cache economics**. Comic Creator Coach is the first integrated educational use case. One maintainer owns the project; sequence independently testable slices, not assignments to multiple developers.

| Epic | Work |
|---|---|
| [EPIC-07 — Dynamic Theme Engine](https://github.com/stevdrey/realtime-voice-lab/issues/39) | [RVL-016 #49](https://github.com/stevdrey/realtime-voice-lab/issues/49), [RVL-017 #50](https://github.com/stevdrey/realtime-voice-lab/issues/50), [RVL-018 #51](https://github.com/stevdrey/realtime-voice-lab/issues/51) |
| [EPIC-08 — Voice-Driven UI Mutation Engine](https://github.com/stevdrey/realtime-voice-lab/issues/40) | [RVL-019 #52](https://github.com/stevdrey/realtime-voice-lab/issues/52), [RVL-020 #53](https://github.com/stevdrey/realtime-voice-lab/issues/53), [RVL-021 #54](https://github.com/stevdrey/realtime-voice-lab/issues/54) |
| [EPIC-09 — Extensible Role Framework](https://github.com/stevdrey/realtime-voice-lab/issues/41) | [RVL-022 #55](https://github.com/stevdrey/realtime-voice-lab/issues/55), [RVL-023 #56](https://github.com/stevdrey/realtime-voice-lab/issues/56) |
| [EPIC-10 — Comic Creator Coach](https://github.com/stevdrey/realtime-voice-lab/issues/42) | [RVL-024 #57](https://github.com/stevdrey/realtime-voice-lab/issues/57), [RVL-025 #58](https://github.com/stevdrey/realtime-voice-lab/issues/58), [RVL-026 #59](https://github.com/stevdrey/realtime-voice-lab/issues/59) |
| [EPIC-11 — Embedded Semantic Memory](https://github.com/stevdrey/realtime-voice-lab/issues/43) | [RVL-027 #60](https://github.com/stevdrey/realtime-voice-lab/issues/60), [RVL-028 #61](https://github.com/stevdrey/realtime-voice-lab/issues/61), [RVL-029 #62](https://github.com/stevdrey/realtime-voice-lab/issues/62), [RVL-030 #63](https://github.com/stevdrey/realtime-voice-lab/issues/63) |
| [EPIC-12 — Multi-Level Cache Engine](https://github.com/stevdrey/realtime-voice-lab/issues/44) | [RVL-031 #64](https://github.com/stevdrey/realtime-voice-lab/issues/64), [RVL-032 #65](https://github.com/stevdrey/realtime-voice-lab/issues/65), [RVL-033 #66](https://github.com/stevdrey/realtime-voice-lab/issues/66) |
| [EPIC-13 — Realtime Context Optimization](https://github.com/stevdrey/realtime-voice-lab/issues/45) | [RVL-034 #67](https://github.com/stevdrey/realtime-voice-lab/issues/67), [RVL-035 #68](https://github.com/stevdrey/realtime-voice-lab/issues/68), [RVL-036 #71](https://github.com/stevdrey/realtime-voice-lab/issues/71) |
| [EPIC-14 — Memory & Cost Observability](https://github.com/stevdrey/realtime-voice-lab/issues/46) | [RVL-037 #72](https://github.com/stevdrey/realtime-voice-lab/issues/72), [RVL-038 #73](https://github.com/stevdrey/realtime-voice-lab/issues/73), [RVL-039 #74](https://github.com/stevdrey/realtime-voice-lab/issues/74) |
| [EPIC-15 — Adaptive User Experience](https://github.com/stevdrey/realtime-voice-lab/issues/47) | [RVL-040 #75](https://github.com/stevdrey/realtime-voice-lab/issues/75), [RVL-041 #76](https://github.com/stevdrey/realtime-voice-lab/issues/76), [RVL-042 #77](https://github.com/stevdrey/realtime-voice-lab/issues/77) |
| [EPIC-16 — Multimodal Interactive Workspace](https://github.com/stevdrey/realtime-voice-lab/issues/48) | [RVL-043 #78](https://github.com/stevdrey/realtime-voice-lab/issues/78), [RVL-044 #79](https://github.com/stevdrey/realtime-voice-lab/issues/79), [RVL-045 #80](https://github.com/stevdrey/realtime-voice-lab/issues/80) |

### Recommended post-MVP implementation order

1. Foundations: #49 → #50 → #51 (theme); #52 → #53 → #54 (UI mutations, after #49 for shared tokens); #55 → #56 (roles). #72 extends usage telemetry (#18) before optimization.
2. Educational slice: #57 after #55/#56; #58 after #57/#52; #59 after #58. Use explicit local project state before semantic memory.
3. Memory: #60 → #61 → #62; #63 after #61. Start with embedded SQLite and exact Java cosine search; benchmark alternatives.
4. Cache/context: #64 after #60/#61; #65 after #62/#64; #66 after #65/#72. #67 and #68 after #72; #71 after #62/#67/#68.
5. Observability: #73 after #62/#64/#72; #74 after #72, final comparisons after #63/#66/#71.
6. Adaptation: #75 after #50/#60; #76 after #54/#56/#75; #77 after #59/#62/#66/#71/#73/#76.
7. Optional multimodal: #78 after #54/#56; #79 after #58/#78; #80 after #78. GPT-Live #21 is not a prerequisite.

### Research gates and verification

- Keep one Java 27/JavaFX Gradle module. Embedded SQLite is explicitly in scope for #60; no external server required. Use provider-independent embedding/vector-store contracts; remote embeddings and local ONNX, sqlite-vec/Lucene alternatives are experimental.
- UI mutations are declarative, validated, atomic, reversible, on the FX Application Thread; preserve audio continuity, accessibility and protected controls. Manual mode is default.
- Separate working history, structured profile/project state, episodic summaries and semantic records. Filter before retrieval; preserve provenance and deletion across caches. Child memory off by default, parental opt-in, no child identifiers or raw audio; synthetic data in CI.
- Compare full history, exact/semantic cache, summary/hybrid retrieval, prompt-cache retention and selective audio history. Never cache side effects or context-dependent replies. Preserve in-flight tool correlations; distinguish reduced context from cached-token pricing; include embedding and summary overhead in net cost.
- Report Precision@K, Recall@K, MRR, false hits, p50/p95, first-audio latency, RAM/disk, context size and cost per minute/session. Record model/config versions, seed, corpus and limitations. No assumed savings or simulated measurements presented as real.
- Offline-first, fake transports/providers, explicit approval for paid calls. Issue-scoped PRs, target 30 changed files or fewer, hard maximum 40.

### Post-MVP checkpoints

- Adaptive UI and role foundation: safe dynamic theme/layout and existing role parity.
- Comic Creator offline slice: guided narrative, structured storyboard and local export.
- Memory and optimization lab: scoped retrieval, bounded caches, context strategies and reproducible measurements.
- Integrated adaptive experience: resume synthetic stories across sessions, retrieve only authorized context, adapt UI within explicit permissions and report cost/quality tradeoffs.
- Optional multimodal extensions: illustrations and additional educational/creative roles, gated by evidence and budget.
