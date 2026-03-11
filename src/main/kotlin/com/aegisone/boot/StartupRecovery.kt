package com.aegisone.boot

import com.aegisone.db.SQLiteSessionRegistry
import com.aegisone.db.StartupReconciliation
import com.aegisone.db.StartupReconciliation.ReconciliationResult
import com.aegisone.execution.ConflictChannel
import com.aegisone.receipt.ReceiptChannel
import com.aegisone.review.ArtifactLockManager
import com.aegisone.review.ArtifactStore
import com.aegisone.review.MemorySteward
import com.aegisone.review.TokenVerifier
import com.aegisone.review.VerifiedToken
import java.sql.Connection

/**
 * Runs the full startup recovery sequence before the coordinator goes ACTIVE.
 *
 * Two phases, in order:
 *
 *   Phase 1 — Receipt/audit reconciliation (StartupReconciliation):
 *     - Orphaned PENDING records → marked FAILED (W1/W2 crash window)
 *     - ActionReceipt rows with no summary → summary regenerated (W3 crash window)
 *     - Sequence gaps → ConflictAlerts emitted (advisory, does not block ACTIVE)
 *     - Unresolved UNAUDITED_IRREVERSIBLE → blocks ACTIVE (human gate required)
 *
 *   Phase 2 — Review session expiry sweep (ExpiryCoordinator):
 *     - Stale ACTIVE sessions (expiry_ms <= now) transitioned to EXPIRED
 *     - For each expired session: locks released, artifacts reverted to SUBMITTED,
 *       ProposalStatusReceipt written to ReceiptChannel
 *
 * Returns StartupRecoveryResult. Callers must check readyForActive before
 * allowing the ExecutionCoordinator to transition to ACTIVE — UNRESOLVED_FAILURES
 * from Phase 1 must block that transition.
 *
 * [now] is injectable so integration tests can force-expire stale sessions
 * without clock manipulation. Production callers use the default.
 *
 * The MemorySteward constructed internally for Phase 2 uses NoOpTokenVerifier.
 * onSessionExpired() never calls verify(), so this is safe. The internal
 * MemorySteward MUST NOT be exposed to callers or used for write() operations.
 *
 * Traces to: I-3 (receipt reconciliation), I-4 (review state reversion)
 * Source: receiptDurabilitySpec-v1.md §7.1, implementationMap §4.4
 */
class StartupRecovery(
    private val receiptConn: Connection,
    private val sessionRegistry: SQLiteSessionRegistry,
    private val artifactStore: ArtifactStore,
    private val lockManager: ArtifactLockManager,
    private val receiptChannel: ReceiptChannel,
    private val conflictChannel: ConflictChannel? = null,
    private val systemEventChannel: SystemEventChannel? = null
) {
    fun run(now: Long = System.currentTimeMillis()): StartupRecoveryResult {
        // Phase 1: reconcile receipt/audit state
        val reconciliation = StartupReconciliation.run(receiptConn, conflictChannel)

        // Phase 2: expire stale review sessions and clean up review state.
        // MemorySteward is constructed here solely for onSessionExpired() access.
        // NoOpTokenVerifier is correct: onSessionExpired() does not call verify().
        val expiryMemorySteward = MemorySteward(
            tokenVerifier       = NoOpTokenVerifier,
            receiptChannel      = receiptChannel,
            artifactLockManager = lockManager,
            artifactStore       = artifactStore
        )
        val expiredSessions = ExpiryCoordinator(sessionRegistry, expiryMemorySteward).sweep(now)

        val result = StartupRecoveryResult(reconciliation, expiredSessions)
        systemEventChannel?.emit(SystemEvent.RecoveryCompleted(
            reconciliationStatus = reconciliation.name,
            expiredSessions      = expiredSessions,
            readyForActive       = result.readyForActive
        ))
        return result
    }

    private object NoOpTokenVerifier : TokenVerifier {
        override fun verify(token: String): VerifiedToken? = null
    }
}

data class StartupRecoveryResult(
    val reconciliation: ReconciliationResult,
    val expiredSessions: Int
) {
    /**
     * True if no repair work was done and no sessions were expired.
     * A clean result means the system was in a fully consistent state at startup.
     */
    val isClean: Boolean
        get() = reconciliation == ReconciliationResult.CLEAN && expiredSessions == 0

    /**
     * True if the ExecutionCoordinator may proceed to ACTIVE.
     * False only when UNRESOLVED_FAILURES are present — those require human review.
     * REPAIRED is not a blocker: repair work completed, system is consistent.
     */
    val readyForActive: Boolean
        get() = reconciliation != ReconciliationResult.UNRESOLVED_FAILURES
}
