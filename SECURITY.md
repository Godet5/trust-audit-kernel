# Security Policy

## Scope

This is a **Phase 0 research harness** for the Aegis One trust-and-audit spine. It is not a deployed production system. The security claims in this repo are bounded by the software threat model described in the README's gap ledger — specifically, filesystem-backed trust roots (G-3) and single-host SQLite enforcement are Phase 0 explicit assumptions.

Before reporting, check whether the concern is already documented in the README under "Where this can still lie." If it is, the right channel is a GitHub issue or a comment on the relevant pinned issue, not a private report.

## Reporting a Vulnerability

If you find a vulnerability that is **not** in the documented gap ledger — particularly one that undermines the five core claims (trust anchor correctness, authority correctness, execution honesty, review honesty, concurrency ceiling) within the stated software threat model — please report it privately before opening a public issue.

**Contact**: Open a private security advisory via GitHub:
`Security` tab → `Advisories` → `New draft security advisory`

Include:
- Which invariant or claim is violated
- A minimal reproduction path (test or scenario)
- Whether the gap is already partially acknowledged somewhere in the codebase

## Response Commitment

This is a research project, not a commercial product. There are no SLA commitments. Reports will be acknowledged and assessed. If the finding is valid and in-scope, it will be documented in the gap ledger and credited in the response.

## Out of Scope

- Physical-access attacks (filesystem tampering, hardware interception)
- Network filesystem or multi-device SQLite sharing
- Byzantine actors with raw storage access
- Anything requiring a deployed production system that does not exist yet

These are Phase 0 explicitly-out-of-scope assumptions documented in the README.
