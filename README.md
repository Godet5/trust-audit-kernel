# AegisOne Harness — Trust and Audit Spine

Phase 0 invariant harness for the Aegis One local-AI sovereign phone.
Proves the trust-and-audit spine holds under nominal conformance, adversarial
fault injection, restart, and concurrent spawn pressure.

**Current state: 182 / 182 tests green.**

---

## Setup

```bash
# Android / Termux only — extract native SQLite library once after clone
bash scripts/extract-sqlite-native.sh

# Run all tests
cd ~/agenticPhone/harness
gradle test --rerun

# Run a specific suite
gradle test --rerun --tests "com.aegisone.concurrency.ConcurrencyEnvelopeE2ETest"
```

On non-Android JVMs the extraction step is not required.

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
Crash recovery repairs all three gaps on next startup.

**Claim 4 — Review honesty**
No human review write is applied unless all seven MemorySteward validation
steps pass: token signature, session active, expiry, artifact existence, lock
ownership, field scope, and receipt write. Session expiry releases locks and
reverts artifact state. Cross-table violations left by crashes are repaired at
startup.

**Claim 5 — Concurrency ceiling**
The Phase 0 ceiling (D-NC3) is enforced under thread contention: max 2 agents
globally (1 PRIMARY + 1 HELPER), max 1 per slot. Recursive spawn from a HELPER
is denied before the ceiling check. Every denial is durable. A 20-thread stress
race never produces a count above 2.

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
| `StaleCapabilityRevocationTest` | G-5: deregistered agent cannot execute stale capability | Deregister then execute, never-registered agent, live check (succeed then block), denial observable as PolicyViolation, no-registry backward compat | 5 |
| `ObservabilityE2ETest` | Every decision is observable | Boot+grant decisions durable, boot failure recorded, recovery telemetry | 3 |
| `ManifestEvolutionE2ETest` | Authority evolves correctly across floor raise | Floor blocks old manifest, v2 resolves, upgrade trail | 2 |
| `DirtyRestartE2ETest` | Recovery gates ACTIVE correctly | Multi-scenario dirty restart, UNRESOLVED_FAILURES blocks ACTIVE | 2 |
| `SessionExpiryE2ETest` | Session expiry releases review authority | Full expiry+revert+receipt path, post-expiry write rejected | 2 |
| `OperatorInspectorE2ETest` | Operator tooling returns correct data | Decisions, receipts, audit failures, sessions, locks, artifacts, recovery summary, violation detection, without-reviewConn degradation | 8 |
| `BootAndActionE2ETest` | Full nominal path end-to-end | Verified boot → grant → execute → durable receipt | 2 |
| `ReviewSliceE2ETest` | Review slice end-to-end | Full write path, grant denied for unknown capability | 2 |
| SQLite + ZoneA integration tests | Backend round-trips | Channel writes, reconciliation steps, session registry, artifact store, lock manager, version floor | 57 |

**Total: 187 / 187**

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
  CapabilityBroker.kt          State machine (UNINITIALIZED/ACTIVE/RESTRICTED) + grant enforcement
  BrokerTypes.kt               CapabilityGrant, IssuedCapability, AgentRole, GrantAuthority
  AuthorityDecision.kt         Sealed: GrantIssued / GrantDenied / SpawnIssued / SpawnDenied

execution/
  ExecutionCoordinator.kt      I-3 enforcer: PENDING → execute → receipt ack → deliver
  ExecutionTypes.kt            ActionRequest, AuditRecord, CoordinatorResult, channel interfaces
  ReversibilityRegistry.kt     Reversibility classification; IrreversibleByDefault is safe default
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
  SystemEvent.kt               BootVerified, BootFailed, RecoveryCompleted

inspect/
  SystemInspector.kt           Read-only operator view: decisions, receipts, audit failures,
                               system events, sessions, locks, artifacts, recovery summary

db/
  SQLiteBootstrap.kt           WAL + synchronous=FULL; schema creation (all tables)
  SQLiteReceiptChannel.kt      Real ReceiptChannel; ack = conn.commit() success
  SQLiteAuditFailureChannel.kt Real AuditFailureChannel; same durability contract
  SQLiteAuthorityDecisionChannel.kt  Records every grant + spawn decision
  SQLiteSystemEventChannel.kt  Records BootVerified, BootFailed, RecoveryCompleted
  SQLiteAgentRegistry.kt       D-NC3 ceiling; synchronized check-and-insert; DB PRIMARY KEY guard
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

**G-1: Single-process ceiling only.**
`SQLiteAgentRegistry` synchronizes on the JVM instance. Two separate JVM
processes sharing the same SQLite file are not synchronized by `@Synchronized`.
The DB PRIMARY KEY would catch duplicate agent_id inserts, but a concurrent
count check across processes can still read stale data and both insert. Phase 0
runs on a single Android process. Multi-process enforcement is deferred.

**G-2: W2 classified as W1 (execution without receipt = FAILED, not UNAUDITED_INDETERMINATE).**
`StartupReconciliation` cannot distinguish "crash before execution" (W1) from
"crash after execution, before receipt" (W2) using only local DB state — the
spec explicitly prohibits external-system inspection at startup. Both are
classified as FAILED. The full `IndeterminateExecutionPolicy` (reversal attempt
+ UNAUDITED_INDETERMINATE escalation) is deferred to the reversibility registry
integration. Documented in `StartupReconciliation.kt` lines 17–26.

**G-3: Zone A is filesystem-backed, not hardware-backed.**
`FileBackedZoneAStore` stores the trust root and platform key as files. The
"platform key" check is byte comparison over file content, not cryptographic
signature verification over a real HSM. The trust anchor claim holds within the
software threat model; physical-access attacks and filesystem tampering by
privileged processes are out of scope for Phase 0.

**G-4: Authority decisions are best-effort.**
`CapabilityBroker.issueGrant()` and `SQLiteAgentRegistry.register()` record
decisions to `AuthorityDecisionChannel` after the grant or denial has already
been made. A channel write failure does not affect the decision outcome. An
attacker who can suppress the channel write can make decisions invisible to the
operator without affecting actual enforcement.

**G-5: ~~AgentRegistry tracks registration, not capability use.~~ CLOSED.**
The coordinator now takes an optional `agentRegistry` parameter (null = legacy
behavior, backward-compatible). When wired, the coordinator checks live registry
state at step_0 — before writing PENDING, before calling the executor. A
deregistered or never-registered agent receives `AGENT_NOT_REGISTERED` and a
durable `PolicyViolation` receipt. The executor is never called. Proven by
`StaleCapabilityRevocationTest` SC-1..SC-5.

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
val inspector = SystemInspector(receiptConn, reviewConn)

inspector.recentDecisions()      // GrantIssued, GrantDenied, SpawnIssued, SpawnDenied
inspector.recentReceipts()       // ActionReceipt, PolicyViolation, ProposalStatusReceipt, …
inspector.recentAuditFailures()  // Pending, Failed, UnauditedIrreversible, SummaryRegenerated
inspector.recentSystemEvents()   // BootVerified, BootFailed, RecoveryCompleted

inspector.sessions()             // all sessions with state (ACTIVE / EXPIRED / CLOSED)
inspector.currentLocks()         // artifact → session; sessionState=null means ghost lock
inspector.artifacts()            // artifact state + lockHolder (null = unlocked)

val summary = inspector.recoverySummary()
summary.isClean                  // true iff no anomalies and no cross-table violations
summary.requiresHumanReview      // true iff UnauditedIrreversible records exist (coordinator must not go ACTIVE)
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

All documents are in `~/agenticPhone/draftPlans/`.

If a test and the spec disagree:
1. Check whether the spec changed.
2. Update the implementation or the test accordingly with a referenced commit.
3. Never silently modify a test to make the build green.

---

## Invariant discipline

Tests under `src/test/kotlin/com/aegisone/invariants/` are specification-derived
contracts. No automated agent may modify them unless the governing spec section
changed. Any modification requires a commit message citing the spec change.

If a test fails: the implementation is wrong, not the test.

Implementation code under `src/main/kotlin/` may change freely to satisfy tests.
Tests are never weakened to satisfy implementations.
