# AegisOne Harness — Trust and Audit Spine

Phase 0 invariant harness for the Aegis One local-AI sovereign phone.
Proves the trust-and-audit spine holds under nominal conformance, adversarial
fault injection, restart, and concurrent spawn pressure.

**Current state: 241 / 241 tests green.**

---

## Setup

**Requirements**: JDK 21+, Gradle 9.x (wrapper included)

```bash
git clone <repo-url>
cd trust-audit-kernel

# Standard JVM (Linux x86_64, macOS, Windows) — no extra steps
./gradlew test

# Android / Termux only — extract the AArch64 native SQLite library once after clone
bash scripts/extract-sqlite-native.sh
./gradlew test

# Run a specific suite
./gradlew test --rerun --tests "com.aegisone.concurrency.ConcurrencyEnvelopeE2ETest"
```

The build detects Termux automatically and routes sqlite-jdbc to the pre-extracted native
library in `native/`. On standard JVMs, sqlite-jdbc uses its own bundled native.
If `native/libsqlitejdbc.so` is absent on Termux, run the extraction script first.

---

## What this system claims

Five claims. Each is either backed by a production component and a test suite
that attacks it, or it isn't — and the gap section says so.

**Claim 1 — Trust anchor correctness**
No broker reaches ACTIVE state without a fully verified manifest: schema valid,
signature matches platform key, version meets anti-rollback floor, timestamp
not in the future. Verification failure at any step returns BootFailed, never
an exception that callers could accidentally treat as success.

**Claim 2 — Authority correctness**
No SYSTEM_POLICY capability grant is issued unless the broker is ACTIVE with a
manifest version that still meets the current floor (re-checked live at grant time,
not cached). Forged, replayed, and version-regressed BootSignals are rejected.

**Claim 3 — Execution honesty**
No governed action is treated as complete unless its receipt is durably
acknowledged by the storage layer. PENDING is written before execution;
ActionReceipt is written before result delivery; summary is written after.
Crash recovery repairs all three gaps on next startup. When receipt write
fails, the outcome is routed by `executor.crashClass`: ATOMIC → FAILED (no
escape claimed); COMPENSATABLE → compensation attempted, then FAILED or
UNAUDITED_IRREVERSIBLE; INDETERMINATE → UNAUDITED_IRREVERSIBLE + conflict
alert. Every real executor must declare its crash class before landing.

**Claim 4 — Review honesty**
No human review write is applied unless all seven MemorySteward validation
steps pass: token signature, session active, expiry, artifact existence, lock
ownership, field scope, and receipt write. Session expiry releases locks and
reverts artifact state. Cross-table violations left by crashes are repaired at
startup.

**Claim 5 — Concurrency ceiling**
The Phase 0 ceiling (D-NC3) is enforced across process boundaries, not just
thread boundaries: max 2 agents globally (1 PRIMARY + 1 HELPER), max 1 per slot.
`BEGIN IMMEDIATE` serializes the check-and-insert across connections (processes);
`synchronized` serializes across threads within a process. Recursive spawn from
a HELPER is denied before the ceiling check. Every denial is durable. A 20-thread
× 4-connection stress race never produces a count above 2.

---

## Invariants

These are merge blockers, not guidelines. A PR that breaks any invariant test
does not ship regardless of other test results.

| # | Invariant | Tests | Production enforcement |
|---|-----------|-------|----------------------|
| I-1 | Manifest verification precedes Broker initialization | T1–T4 | `TrustInit` → `BootSignal` → `CapabilityBroker.initialize()` |
| I-2 | No SYSTEM_POLICY grant without verified, non-rolled-back manifest | T1–T5 | `CapabilityBroker.issueGrant()` + live `VersionFloorProvider` re-check |
| I-3 | No governed action completes without durable receipt acknowledgment | T1–T6 | `ExecutionCoordinator` 5-step sequence; ack = `conn.commit()` |
| I-4 | No review write unless all 7 MEMORY_STEWARD validation steps pass | T1–T7 | `MemorySteward.write()` + `ExpiryCoordinator` + `ReviewCrossTableReconciliation` |
| D-NC3 | Phase 0 ceiling: max 2 agents (1 PRIMARY + 1 HELPER), no recursive spawn | CE-1..CE-8 | `SQLiteAgentRegistry` `@Synchronized` check-and-insert + DB PRIMARY KEY |

---

## Adversarial test index

Every suite here attacks a specific failure mode. Test counts are
invocations, not test methods — repeated tests (×N) are counted separately.

| Suite | What was attacked | How | Invocations |
|-------|-------------------|-----|-------------|
| `I1ManifestBeforeBrokerTest` | Broker operates without verified manifest | Forged signal, failed verification, raw-field leakage, re-verification staleness | 4 |
| `I2NoSystemPolicyWithoutManifestTest` | SYSTEM_POLICY grant bypasses manifest | Restricted state, non-enumerated role, stale floor, forbidden HELPER_SPECIALIST spawn, forged/replayed/regression signal | 5 |
| `I3PendingReceiptExecuteAcknowledgeTest` | Execution proceeds without durable receipt | Unavailable audit channel, failed receipt write (reversible + irreversible), delivery gating, sequence monotonicity, mid-session reset | 6 |
| `I4ReviewWriteValidationTest` | Review write bypasses validation | Invalid token, expired session, tampered expiry, wrong lock holder, out-of-scope field, lock expiry with receipt | 7 |
| `AdversarialStorageFaultsE2ETest` | Receipt boundary lies under storage failure | Closed connection (audit + receipt), SQLITE_BUSY write-lock contention, duplicate receipt_id | 5 |
| `ManifestCorruptionE2ETest` | Trust anchor survives corruption | Truncated JSON, schema flag false, wrong signature, corrupted version_floor, deleted platform key, future timestamp, anti-rollback, recovery from corruption | 8 |
| `CrossTableReviewCorruptionE2ETest` | Review state survives cross-table crash | UNDER_REVIEW with no lock, ghost lock (absent session), EXPIRED session with lock, all three simultaneously, clean state (no spurious repair) | 5 |
| `KillWindowRealismE2ETest` | Crash windows W1/W3 and review violations survive kill | Real writes committed, connection closed, fresh connection, reconciliation repairs | 5 |
| `ConcurrencyEnvelopeE2ETest` | D-NC3 ceiling holds under thread contention | Two concurrent HELPER spawns (×10), full ceiling third spawn (×10), concurrent deregister+spawn (×10), duplicate agent_id race (×10), recursive spawn, recursive spawn with open slot, denial observability, 20-thread stress race (×5) | 49 |
| `MultiProcessCeilingTest` | G-1: Ceiling holds across process boundaries | Two connections race (×10), per-slot across connections, deregister/register across connections, 4-conn×5-thread stress (×5), cross-connection duplicate id | 18 |
| `StaleCapabilityRevocationTest` | G-5: deregistered agent cannot execute stale capability | Deregister then execute, never-registered agent, live check (succeed then block), denial observable as PolicyViolation, no-registry backward compat | 5 |
| `RegistrationAtomicityTest` | G-5a: step_0 check and step_1 PENDING write are fused (no TOCTOU) | Deregistered before execute, registered at execute, deregister after gate closes, concurrent deregister race (×10), no-registry backward compat | 14 |
| `AuthorityDecisionFailClosedTest` | P5: Grant blocked when decision record write fails | GrantIssued write failure blocks grant, GrantDenied write failure surfaces Anomaly, channel fails mid-session, per-call check | 5 |
| `BrokerStateTransitionDurabilityTest` | P6: Broker state changes are durable | ACTIVE→RESTRICTED emits BrokerStateChanged, no event on clean grant, emission timing, optional channel, manifest version attribution | 5 |
| `ObservabilityE2ETest` | Every decision is observable | Boot+grant decisions durable, boot failure recorded, recovery telemetry, broker state transition persisted | 4 |
| `ManifestEvolutionE2ETest` | Authority evolves correctly across floor raise | Floor blocks old manifest, v2 resolves, upgrade trail | 2 |
| `DirtyRestartE2ETest` | Recovery gates ACTIVE correctly | Multi-scenario dirty restart, UNRESOLVED_FAILURES blocks ACTIVE | 2 |
| `SessionExpiryE2ETest` | Session expiry releases review authority | Full expiry+revert+receipt path, post-expiry write rejected | 2 |
| `OperatorInspectorE2ETest` | Operator tooling returns correct data | Decisions, receipts, audit failures, sessions, locks, artifacts, recovery summary, violation detection, degraded-mode requiresHumanReview | 8 |
| `RecoveryReviewCoverageTest` | P4: Operator surface never lies under degraded/violated conditions | Cross-table violation triggers review, degraded forces review, UnauditedIrreversible blocks readyForActive, inspector state transition correctness | 4 |
| `BootAndActionE2ETest` | Full nominal path end-to-end | Verified boot → grant → execute → durable receipt | 2 |
| `ReviewSliceE2ETest` | Review slice end-to-end | Full write path, grant denied for unknown capability | 2 |
| `CrashSemanticsTest` | Phase 1 readiness gate: crash class routing in coordinator | ATOMIC→FAILED, COMPENSATABLE+success→FAILED, COMPENSATABLE+fail→UNAUDITED_IRREVERSIBLE, INDETERMINATE→UNAUDITED_IRREVERSIBLE, execution failure all classes→FAILED, default class is INDETERMINATE | 6 |
| SQLite + ZoneA integration tests | Backend round-trips | Channel writes, reconciliation steps, session registry, artifact store, lock manager (incl. G-11 non-stealing), version floor | 58 |

**Total: 241 / 241**

---

## Production code map

```
src/main/kotlin/com/aegisone/

trust/
  TrustInit.kt                 Boot verification (schema → sig → floor → timestamp → derive)
  BootSignal.kt                Sealed: Verified (origin-bound) / Failed (step + reason)
  TrustInitIdentity.kt         Opaque issuer token; reference equality = authority binding
  ZoneAStore.kt                Zone A access interface (acquireAccess / release)
  Manifest.kt                  Runtime manifest model — lives only during TrustInit
  VersionFloorProvider.kt      Anti-rollback floor query; re-read live at grant time

broker/
  CapabilityBroker.kt          State machine + grant enforcement; fail-closed authority decisions;
                               emits BrokerStateChanged on ACTIVE→RESTRICTED
  BrokerTypes.kt               CapabilityGrant, IssuedCapability, AgentRole, GrantAuthority
  AuthorityDecision.kt         Sealed: GrantIssued / GrantDenied / SpawnIssued / SpawnDenied

execution/
  ExecutionCoordinator.kt      I-3 enforcer: PENDING → execute → receipt ack → deliver
  ExecutionTypes.kt            ActionRequest, AuditRecord, CoordinatorResult, channel interfaces
  ExecutionOutcomeRegistry.kt  Compensation registry; NoCompensationRegistry is safe default (fail toward UNAUDITED_IRREVERSIBLE)
  AgentRegistry.kt             Interface: register / deregister / activeCount / slotOf
  AgentRegistryTypes.kt        AgentSlot, RegistrationResult, DenialReason, ActiveAgentRow

receipt/
  ReceiptChannel.kt            Write interface + sealed Receipt types (ActionReceipt, PolicyViolation,
                               ManifestFailure, Anomaly, ProposalStatusReceipt)

review/
  MemorySteward.kt             7-step write validation + session expiry reversion
  ReviewTypes.kt               ArtifactState, SessionEntry, field authority interfaces

boot/
  BootOrchestrator.kt          TrustInit → BootSignal → CapabilityBroker (only path to ACTIVE)
  StartupRecovery.kt           3-phase: receipt reconciliation → expiry sweep → cross-table repair
  ExpiryCoordinator.kt         Session expiry sweep: ACTIVE→EXPIRED, locks released, receipts written
  ReviewCrossTableReconciliation.kt  Repairs 3 violation classes invisible to expiry sweep
  SystemEvent.kt               BootVerified, BootFailed, RecoveryCompleted, BrokerStateChanged

inspect/
  SystemInspector.kt           Read-only operator view: decisions, receipts, audit failures,
                               system events, sessions, locks, artifacts, recovery summary

db/
  SQLiteBootstrap.kt           WAL + synchronous=FULL; schema creation (all tables)
  SQLiteReceiptChannel.kt      Real ReceiptChannel; ack = conn.commit() success
  SQLiteAuditFailureChannel.kt Real AuditFailureChannel; same durability contract
  SQLiteAuthorityDecisionChannel.kt  Records every grant + spawn decision
  SQLiteSystemEventChannel.kt  Records BootVerified, BootFailed, RecoveryCompleted, BrokerStateChanged
  SQLiteAgentRegistry.kt       D-NC3 ceiling; BEGIN IMMEDIATE for cross-process atomicity;
                               synchronized for intra-process; DB PRIMARY KEY safety net
  SQLiteSessionRegistry.kt     Session lifecycle (ACTIVE/EXPIRED/CLOSED)
  SQLiteArtifactStore.kt       Artifact state and field storage
  SQLiteArtifactLockManager.kt Exclusive artifact lock management
  ReviewDbBootstrap.kt         Review DB schema (sessions, artifacts, locks)
  StartupReconciliation.kt     §7.1 six-step sequence (W1/W2 orphan repair, W3 summary regen,
                               sequence gap alerting, UNAUDITED_IRREVERSIBLE block)

zonea/
  FileBackedZoneAStore.kt      Phase 0 Zone A (filesystem-backed; no HSM in Phase 0)
  FileBackedVersionFloorProvider.kt  Anti-rollback floor storage
  ManifestRecord.kt            Provisioned manifest structure
```

---

## Where this can still lie

These are known residual gaps. They are not oversights — they are deliberate
Phase 0 deferences with documented rationale. Each represents a risk a hostile
reader should weigh.

**G-1: ~~Single-process ceiling only.~~ CLOSED.**
`SQLiteAgentRegistry.register()` wraps the entire check-and-insert sequence
in a `BEGIN IMMEDIATE` transaction. This acquires SQLite's reserved lock before
any reads, serializing the ceiling check across connections (= processes).
Dual-layer atomicity: `synchronized(shared)` for intra-process threads,
`BEGIN IMMEDIATE` for inter-process connections. Proven by
`MultiProcessCeilingTest` MP-1..MP-5 (20-thread × 4-connection stress test).

**G-2: W2 still classified as FAILED at startup (not UNAUDITED_INDETERMINATE).**
`StartupReconciliation` cannot distinguish "crash before execution" (W1) from
"crash after execution, before receipt" (W2) using only local DB state — the
spec explicitly prohibits external-system inspection at startup. Both are
classified as FAILED. The `CrashClass` enum and `ExecutionOutcomeRegistry`
interface are now in place — the classification model exists and is enforced
for live execution. Startup reconciliation routing is deferred to Phase 1 when
the reversibility registry is wired to real executors with declared crash classes.
Documented in `StartupReconciliation.kt`.

**G-3: Zone A is filesystem-backed, not hardware-backed.**
`FileBackedZoneAStore` stores the trust root and platform key as files. The
"platform key" check is byte comparison over file content, not cryptographic
signature verification over a real HSM. The trust anchor claim holds within the
software threat model; physical-access attacks and filesystem tampering by
privileged processes are out of scope for Phase 0.

**G-4: ~~Authority decisions are best-effort.~~ CLOSED.**
`AuthorityDecisionChannel` is now a required parameter (no null default).
`CapabilityBroker.issueGrant()` enforces fail-closed: GrantIssued write failure
blocks the grant (P5). GrantDenied write failure surfaces an Anomaly but the
denial still holds. Proven by `AuthorityDecisionFailClosedTest` AD-1..AD-5.

**G-5: ~~AgentRegistry tracks registration, not capability use.~~ CLOSED.**
The coordinator now takes an optional `agentRegistry` parameter (null = legacy
behavior, backward-compatible). When wired, the coordinator checks live registry
state at step_0 — before writing PENDING, before calling the executor. A
deregistered or never-registered agent receives `AGENT_NOT_REGISTERED` and a
durable `PolicyViolation` receipt. The executor is never called. Proven by
`StaleCapabilityRevocationTest` SC-1..SC-5.

**G-5a: ~~TOCTOU between step_0 (registry check) and step_1 (PENDING write).~~ CLOSED.**
`AgentRegistry.checkAndBegin()` fuses the registration check and PENDING write into
a single registry-locked unit. The check and PENDING write execute while
`synchronized(shared)` is held — a concurrent `deregister()` cannot interleave between
them. Proven by `RegistrationAtomicityTest` RA-1..RA-4 (×10 concurrent stress).

**G-11: ~~`INSERT OR REPLACE` allowed any session to steal an artifact lock.~~ CLOSED.**
`SQLiteArtifactLockManager.lock()` now uses `INSERT OR IGNORE` with a holder check.
Returns true if acquired or same-session re-lock (idempotent). Returns false if a
different session holds the lock — the existing holder is not displaced. Proven by
`ArtifactStoreIntegrationTest` ALM-6 (conflict rejected) and ALM-8 (idempotent re-lock).

**G-6: Sequence gap alerting is advisory.**
`StartupReconciliation` step 4 detects sequence gaps and emits `ConflictAlert`s
but does not block the coordinator from going ACTIVE. An agent that deliberately
drops receipts (or a storage layer that loses writes) produces gaps that are
reported but not acted upon.

**G-7: No live manifest refresh.**
Once a `BootSignal.Verified` is accepted, the broker holds the derived grant
list and agent enumeration until the next boot. If the manifest file is modified
post-boot (e.g., a capability added), the running broker does not see it. The
version floor check at grant time catches manifest downgrades; it does not catch
post-boot manifest additions.

---

## Operator entry points

Inspect live system state without raw SQL or touching the storage layer.

```kotlin
// Both DBs wired — full view
val inspector = SystemInspector(receiptShared, reviewShared)

inspector.recentDecisions()      // GrantIssued, GrantDenied, SpawnIssued, SpawnDenied
inspector.recentReceipts()       // ActionReceipt, PolicyViolation, ProposalStatusReceipt, …
inspector.recentAuditFailures()  // Pending, Failed, UnauditedIrreversible, SummaryRegenerated
inspector.recentSystemEvents()   // BootVerified, BootFailed, RecoveryCompleted, BrokerStateChanged

inspector.sessions()             // all sessions with state (ACTIVE / EXPIRED / CLOSED)
inspector.currentLocks()         // artifact → session; sessionState=null means ghost lock
inspector.artifacts()            // artifact state + lockHolder (null = unlocked)

val summary = inspector.recoverySummary()
summary.isClean                  // true iff no anomalies and no cross-table violations
summary.requiresHumanReview      // true iff UnauditedIrreversible > 0, cross-table violations > 0, or degraded
summary.violationA               // UNDER_REVIEW artifacts with no lock
summary.violationB               // ghost locks (lock → absent session)
summary.violationC               // EXPIRED/CLOSED sessions with outstanding locks
```

`SystemInspector` is read-only. It does not write, mutate, or recover state.
For recovery, use `StartupRecovery.run()`.

---

## Spec anchors

| Document | Role |
|----------|------|
| `agentPolicyEngine-v2.1.md` | Canonical policy engine; authority source for I-1, I-2, D-NC3 |
| `implementationMap-trust-and-audit-v1.md` | Module decomposition; integration test specifications |
| `receiptDurabilitySpec-v1.md` | Durability contract; crash window taxonomy; reconciliation sequence |
| `reviewSystemSpec-v1.md` | Review interface; 7-step validation; session auth; field authority |
| `threatModel-v1.1.md` | Threat model baseline |

All documents are in the sibling `draftPlans/` directory of this repo's parent (not published here; the harness is self-contained as a test artifact).

If a test and the spec disagree:
1. Check whether the spec changed.
2. Update the implementation or the test accordingly with a referenced commit.
3. Never silently modify a test to make the build green.

---

## Phase 1 executor gate

Phase 0 is complete when the system's claims are the claims the tests actually verify.
The Phase 1 executor gate exists to prevent crash-semantics drift under feature pressure.
The first executor validates the gate. The fourth executor is where teams usually start
calling the gate optional. It is not optional.

A Phase 1 executor is not considered landed until:

1. `crashClass` is explicitly declared with a one-sentence justification in the PR
2. COMPENSATABLE executors have a registered compensator wired
3. A live routing test proves the declared class produces the correct `CoordinatorResult`
4. A kill-window E2E test proves startup reconciliation routes by `crashClass`, not Phase 0 fallback
5. The kill-window test class is named `<ExecutorName>CrashRecoveryE2ETest` for discoverability

Criterion 4 is a merge-blocker because "we'll add kill-window tests later" translates to "we won't."
An executor with a kill-window test buried in a generic suite does not satisfy criterion 5 for
discovery purposes — a reviewer must be able to find the proof in 5 seconds by scanning the test index.

Spec anchor: `receiptDurabilitySpec-v1.md §4.5.4–4.5.5`

---

## External review targets

This is a Phase 0 research harness, not a production system. External critique is
more useful than praise. If you're reading this looking for something to attack, here
are the four areas where honest pressure is most valuable:

**1. Crash semantics model** (`receiptDurabilitySpec-v1.md §4.5`, `CrashSemanticsTest`)
The `CrashClass` enum (ATOMIC / COMPENSATABLE / INDETERMINATE) is new. The live
execution routing is tested. The startup reconciliation routing is not yet updated to
use declared crash classes — it still classifies all orphaned PENDING as FAILED (G-2,
documented gap). The question worth asking: is the three-class model the right
abstraction, or does real executor behavior reveal a fourth case we haven't named?

**2. Observability honesty** (`ObservabilityE2ETest`, `OperatorInspectorE2ETest`, `SystemInspector`)
Every decision, grant, receipt, and state transition is supposed to be durable and
queryable. The `SystemInspector` is read-only and correct under clean conditions. The
interesting question is degraded mode: does the operator surface tell the truth when
the review DB is absent, when a session is expired but its lock persists, or when an
UNAUDITED_IRREVERSIBLE event exists? `RecoveryReviewCoverageTest` and `OperatorInspectorE2ETest`
OI-8 test this. Review them adversarially.

**3. Multi-process enforcement assumptions** (`MultiProcessCeilingTest`, `SQLiteAgentRegistry`)
The ceiling (max 2 agents globally) is enforced with `BEGIN IMMEDIATE` + `synchronized`.
This works against SQLite connections in the same process and across separate processes
using the same DB file. It does not cover: multiple devices sharing a DB over a network
filesystem, a process that bypasses SQLite and writes directly to the DB file, or
Byzantine actors with raw storage access. Those are Phase 0 explicitly-out-of-scope
assumptions. If you believe one of them is actually in-scope for the threat model,
`threatModel-v1.1.md` is the place to argue it.

**4. Operator truth under degraded conditions** (`RecoveryReviewCoverageTest`, `StartupRecovery`)
`RecoverySummary.requiresHumanReview` gates human attention. It fires on
`UnauditedIrreversible > 0`, cross-table violations, or degraded mode (review DB absent).
The question: are there reachable states where `requiresHumanReview` is false but a
human should actually look? The test suite covers the documented violation types. It may
not cover compound degraded states that weren't anticipated.

Critique against these four areas is more useful than general commentary. The gap ledger
("Where this can still lie") documents the known limits. Useful external review finds
limits that aren't in that ledger.

---

## Invariant discipline

Tests under `src/test/kotlin/com/aegisone/invariants/` are specification-derived
contracts. No automated agent may modify them unless the governing spec section
changed. Any modification requires a commit message citing the spec change.

If a test fails: the implementation is wrong, not the test.

Implementation code under `src/main/kotlin/` may change freely to satisfy tests.
Tests are never weakened to satisfy implementations.
