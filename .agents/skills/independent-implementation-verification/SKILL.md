---
name: independent-implementation-verification
description: Check an exact issue candidate against code, acceptance criteria and offline evidence before PR publication.
---

# Independent implementation verification

Record the intended base and candidate head or working tree diff. Inspect the candidate against the issue, relevant ADRs, existing caller behavior and changed tests. Seek falsifiable evidence for each material acceptance criterion.

For provider data, local tools, FX state or spend limits, follow the input to its effect, including errors and cleanup. Run targeted synthetic checks when they can resolve a meaningful uncertainty. Keep credentials and paid calls out of verification.

Use a reviewer authorized by the session when available, passing the candidate and acceptance criteria without the implementer's verdict. Otherwise perform a separate verification pass and disclose the lack of an independent reviewer. Give the result and unresolved evidence. Revalidate after a material candidate change.
