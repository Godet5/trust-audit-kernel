# AegisOne Harness — Invariant Verification

## Setup (Termux / Android)

sqlite-jdbc bundles a native library that it extracts to `/tmp` at runtime.
On Android, `/tmp` is outside the JVM's trusted linker namespace — `dlopen()` fails.
Extract it to the correct location once after clone:

```
bash harness/scripts/extract-sqlite-native.sh
```

This places `native/libsqlitejdbc.so` (gitignored) in the right path.
The Gradle test task is already configured to use it via `org.sqlite.lib.path`.

On non-Android JVMs this step is not required (extraction to `/tmp` works normally).

## Quick sanity check

```
cd ~/agenticPhone/harness
gradle test --rerun
```

## System boot model

1. TrustInit reads and verifies the manifest from Zone A (schema → signature → version floor → timestamp).
2. TrustInit derives runtime objects (grant list, agent enumeration) and clears the manifest from memory.
3. TrustInit issues an origin-bound `BootSignal` (with `TrustInitIdentity` issuer reference). Never raw manifest.
4. Broker validates signal origin (reference equality), sequence monotonicity, and version non-regression on `initialize()`.
5. Broker enforces SYSTEM_POLICY grants against: manifest grant list, manifest agent enumeration, and current version floor (re-checked at grant time, not cached).
6. All denials produce receipts via ReceiptChannel.
7. ExecutionCoordinator enforces I-3: PENDING → execute → receipt ack → deliver via ResultSink.

**RESTRICTED transition rule**: Broker transitions ACTIVE → RESTRICTED if manifest_version < current floor at grant time. Re-verification via TrustInit is required to return to ACTIVE. This is enforced by I2-T3.

```
Zone A
  │
  ▼
TrustInit (identity: TrustInitIdentity)
  │  issues BootSignal (origin-bound, sequenced)
  ▼
CapabilityBroker ──────────► ReceiptChannel
  │          ▲
  │          └── VersionFloorProvider (live re-check at grant time)
  ▼
IssuedCapability

ActionRequest
  │
  ▼
ExecutionCoordinator
  │  step_1: PENDING → AuditFailureChannel
  │  step_2: execute
  │  step_3: ActionReceipt → ReceiptChannel (ack required)
  │  step_5: deliver → ResultSink (only after step_3 ack)
  ▼
CoordinatorResult
```

## Current coverage

| Invariant | Description | Tests | Status |
|-----------|-------------|-------|--------|
| I-1 | Manifest verification precedes Broker initialization | T1–T4 | Complete |
| I-2 | No SYSTEM_POLICY grants without verified, non-rolled-back manifest | T1–T5 | Complete |
| I-3 | No governed action completes without durable receipt acknowledgment | T1–T6 | **Complete** |
| I-4 | No review write unless all 7 MEMORY_STEWARD validation steps pass | T1–T7 | **Complete** |

**Invariant tests: 22 / 22**

Test distribution — I-1: 4 · I-2: 5 · I-3: 6 · I-4: 7 · Total: 22

### SQLite backend integration tests

| Suite | Tests | Status |
|-------|-------|--------|
| `SQLiteChannelIntegrationTest` | RC-1..4 · AFC-1..3 · SR-1..4 | **11 / 11** |

**Total passing: 33 / 33**

## Implementation status

| Layer | Backend | Status |
|-------|---------|--------|
| ReceiptChannel | `SQLiteReceiptChannel` — WAL + `synchronous=FULL` | **Real** |
| AuditFailureChannel | `SQLiteAuditFailureChannel` — WAL + `synchronous=FULL` | **Real** |
| StartupReconciliation | Steps 1–2 and 5 real SQL; steps 3–4 honest stubs | **Partial** |
| ZoneAStore | In-memory test double | Fake |
| ReviewSystem | In-memory test doubles | Fake |

## Source map

```
src/main/kotlin/com/aegisone/
  trust/
    TrustInit.kt              Boot verification sequence (phases 0–7)
    ZoneAStore.kt             Zone A access interface
    Manifest.kt               Manifest model — lives only during TrustInit
    VersionFloorProvider.kt   Floor query interface; checked at grant time
  broker/
    CapabilityBroker.kt       Broker state machine + grant enforcement
    BrokerTypes.kt            Shared domain types (roles, grants, results)
  receipt/
    ReceiptChannel.kt         Receipt write interface + sealed Receipt types
  db/
    SQLiteBootstrap.kt        Opens connection; applies WAL + synchronous=FULL; creates tables
    SQLiteReceiptChannel.kt   Real ReceiptChannel — true only on commit() success
    SQLiteAuditFailureChannel.kt  Real AuditFailureChannel — same durability contract
    StartupReconciliation.kt  §7.1 six-step sequence; steps 1-2 and 5 real; 3-4 stubs

src/test/kotlin/com/aegisone/invariants/
  TestZoneAStore.kt                       In-memory Zone A + TestManifests factory
  MutableTestZoneAStore.kt                Mutable Zone A double for I1-T4 re-verification
  TestReceiptChannel.kt                   In-memory receipt collector with assertion helpers
  TestAuditFailureChannel.kt              In-memory audit channel with availability flag
  TestConflictChannel.kt                  Collects ConflictAlert for assertion
  TestReversibilityRegistry.kt            Explicit capability registrations; tracks reverseCallCount
  TrackingResultSink.kt                   Counts deliver() calls; used in I3-T4
  MutableVersionFloorProvider.kt          Mutable floor double for I2-T3 scenario
  I1ManifestBeforeBrokerTest.kt           I-1 tests (4)
  I2NoSystemPolicyWithoutManifestTest.kt  I-2 tests (5)
  I3PendingReceiptExecuteAcknowledgeTest.kt  I-3 tests (4 of 6: T1–T4)

src/test/kotlin/com/aegisone/execution/
  TrackingExecutor.kt                     Tracks wasExecuted and callCount
```

## Spec anchor

Tests trace to `~/agenticPhone/draftPlans/implementationMap-trust-and-audit-v1.md §6`.

If a test and the spec disagree:
1. Check if the spec changed.
2. Update the test or implementation accordingly.
3. Never silently modify behavior to satisfy a failing test.

---

## Invariant Safety Rules

The harness exists to prove invariants, not to pass builds.

### Tests are contract tests

Files under `src/test/kotlin/com/aegisone/invariants/` are specification-derived contracts.
**No automated agent (Claude, GPT, IDE assistant, linter, etc.) may modify these files**
unless the governing spec section has changed.

If a test fails, the default assumption is: **the implementation is wrong, not the test.**

Any test change must reference the spec section that changed. Example commit message:

```
spec-change: implementationMap §6 updated I-2 role rule
Updated: I2NoSystemPolicyWithoutManifestTest.kt
```

### Implementation freedom

Code under `src/main/kotlin/com/aegisone/` may change freely to satisfy invariant tests.
Tests must never be modified simply to make builds green.

### Invariant discipline (one test at a time)

1. Write the test
2. Run — expect failure
3. Minimal implementation change
4. Green
5. Commit
6. Repeat

Never implement multiple invariants at once.

### Why this matters

LLM assistants and IDE auto-fix tools frequently propose changes like:
- "update failing assertion"
- "remove redundant check"
- "refactor test to match new logic"

Those suggestions silently break invariant enforcement. When a test fails, the invariant
just saved you from a bug. Respect it.

When you reach I-3 (durable receipt acknowledgment) and I-4 (memory steward write validation),
these tests are the only reliable enforcement preventing subtle security regressions.
