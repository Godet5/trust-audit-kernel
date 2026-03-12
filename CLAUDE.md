# CLAUDE.md — trust-audit-kernel

Project-specific rules for Claude Code and GitHub Actions automation.

## What this repo is

Phase 0 research harness for the Aegis One trust-and-audit spine. It is a test-heavy,
fail-closed, SQLite-backed audit spine prototype. It is not a deployed production system.

## Hard rules — always

1. **Tests are specs.** Files under `src/test/kotlin/com/aegisone/invariants/` are
   specification-derived contracts. They may not be modified unless the governing spec
   section changed. Any modification requires a commit message citing the spec change
   by section number.

2. **Implementations satisfy tests. Tests are never weakened to satisfy implementations.**

3. **No fast-path bypass of receipt acknowledgment.** The PENDING → execute → ack →
   deliver sequence in `ExecutionCoordinator` is absolute.

4. **No module outside TrustInit holds manifest-derived data beyond issued Capability objects.**

5. **Every new executor requires an explicit `crashClass` declaration, a live routing test,
   and a kill-window E2E test named `<ExecutorName>CrashRecoveryE2ETest`.**

## External review triage policy

When running in GitHub Actions triage mode (external-review-watch workflow):

- **Never propose fixes unless explicitly asked by a maintainer.**
- **Never open issues automatically.** Label existing issues; do not create new ones.
- **Never convert reserve topics into public design priorities** without human decision.
- **Never weaken a label** to avoid appearing uncertain. If judgment is required, apply
  `needs-human-review`.
- **Label only when evidence is concrete.** Broad commentary with no test reference or
  reproduction path is `noise`.
- **Ignore maintainer housekeeping comments** ("thanks", "looking into this", "fixed in #N",
  "+1"). Do not post triage output on comments that add no new technical signal.
- **One triage comment per issue** unless the issue is substantially edited or a comment
  adds genuinely new signal. Do not accumulate duplicate triage comments.

## Gap ledger discipline

The README gap ledger documents known limits. Triage may suggest `gap-ledger-candidate`
but humans decide whether an entry is added. Do not silently amend the gap ledger.

## Tone

Neutral and concise. Do not argue with reporters. Do not claim issues are invalid without
evidence. Do not promise fixes or timelines.
