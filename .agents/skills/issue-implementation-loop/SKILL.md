---
name: issue-implementation-loop
description: Implement a specified GitHub issue through scoped changes, exact-candidate verification and pull request publication.
---

# Issue implementation loop

1. Read the issue, linked decisions, current base and any existing PR. Reuse a suitable issue branch and preserve work already there.
2. Translate the acceptance list into checks. Apply the task workflow and relevant project skills. Verify offline and record meaningful gaps.
3. Apply independent implementation verification to the exact candidate. Fix concrete blockers and recheck affected behavior and the final candidate. Stop once the evidence satisfies the acceptance list and remaining gaps are accurately recorded.
4. Use the PR creation skill to draft or publish as authorized. A request to implement an issue through PR creation leaves the issue and PR lifecycle to GitHub and the maintainer. Obtain explicit merge authorization before merging.
