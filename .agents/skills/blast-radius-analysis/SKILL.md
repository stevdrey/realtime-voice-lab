---
name: blast-radius-analysis
description: Trace desktop changes that cross protocol, audio, FX state or local tools, or modify privacy and budget guarantees.
---

# Blast-radius analysis

For a security invariant or a change crossing multiple critical boundaries, trace the affected flow from input to observable effect. Include state ownership, where validation and authorization happen, and how cancellation and errors propagate.

Select the assumptions that would materially break acceptance criteria, privacy, or spend limits if false. Verify them against source and focused offline tests or the synthetic desktop journey. Record the touched boundaries, verified facts and uncovered cases in the PR. Keep this proportional to the change.
