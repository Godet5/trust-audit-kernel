# Contributing

This is a Phase 0 research harness. Contributions that tighten the system's stated claims, close documented gaps, or add adversarial test coverage are welcome. Contributions that relax enforcement, add permissive defaults, or weaken invariant tests are not.

## Before Opening a PR

Read the README's invariant table, adversarial test index, and gap ledger. Understand what the system currently claims and where it openly says it may still lie. A PR that ignores the gap ledger is likely to miss the point.

## Hard Rules (merge blockers)

These are not guidelines. A PR that violates any of these does not ship:

1. **No module outside TrustInit holds manifest-derived data beyond issued Capability objects.**
   Manifest content must not leak past the verification boundary.

2. **No fast-path bypass of receipt acknowledgment for any action class.**
   The PENDING → execute → ack → deliver sequence in `ExecutionCoordinator` is absolute. There is no class of action for which this sequence is optional.

3. **Every new executor requires an explicit `crashClass` declaration with a one-sentence justification, a live routing test, and a kill-window E2E test named `<ExecutorName>CrashRecoveryE2ETest`.**
   See the Phase 1 executor gate section in the README. Criterion 4 (kill-window test) is a merge blocker because "we'll add it later" translates to "we won't."

## Invariant Tests Are Specs

Tests under `src/test/kotlin/com/aegisone/invariants/` are specification-derived contracts, not implementation tests. They may not be modified unless the governing spec section changed. Any modification requires a commit message citing the spec change by section number.

If a test fails: **the implementation is wrong, not the test.**

## Gap Ledger Protocol

If you close a documented gap:
- Update the README gap entry from the open description to a `CLOSED` entry with a one-paragraph explanation of the fix
- Reference the test(s) that prove closure by name
- Do not remove the entry — closed gaps stay visible so reviewers understand the system's history

If you discover a new gap:
- Open an issue first
- Add it to the README gap ledger before the PR lands
- Document whether it is an intentional Phase 0 deferral or an unintended oversight

## Adding Tests

Adversarial tests are preferred over nominal-path tests. The adversarial test index in the README should be updated when a new suite is added. Include: what was attacked, how, and the invocation count.

Repeated tests (×N stress runs) count as N invocations in the total — update the count accordingly.

## Naming Conventions

- Artifact versions: `<type>-v<N>` (e.g., `threatModel-v2.md`)
- Kill-window tests: `<ExecutorName>CrashRecoveryE2ETest`
- Integration test suites: descriptive of what is attacked, not what is verified

## Commit Messages

Include a one-line summary of what changed and why. If the change closes a gap or modifies an invariant, say so explicitly. If the change updates a spec anchor, cite the section number.
