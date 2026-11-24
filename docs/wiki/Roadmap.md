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
