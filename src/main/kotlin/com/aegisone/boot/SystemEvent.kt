package com.aegisone.boot

/**
 * Structured lifecycle events emitted by orchestration components.
 *
 * These events expose the operational state of the trust spine at key transition
 * points — boot, recovery — in a queryable, durable form. They are a separate
 * stream from the execution receipt trail and the authority decision trail.
 *
 * BootVerified        — emitted by BootOrchestrator on successful boot
 * BootFailed          — emitted by BootOrchestrator on any boot failure
 * RecoveryCompleted   — emitted by StartupRecovery after both reconciliation phases
 * BrokerStateChanged  — emitted by CapabilityBroker on authority state transitions
 *                       (e.g. ACTIVE → RESTRICTED on floor mismatch)
 *
 * Session expiry events (SESSION_EXPIRED, LOCK_RELEASED, STATE_REVERTED) are
 * already captured as ProposalStatusReceipt entries in the receipt channel and
 * are not duplicated here. The lifecycle events here are for orchestration-level
 * transitions, not per-artifact operational events.
 */
sealed class SystemEvent {
    abstract val timestamp: Long

    data class BootVerified(
        val manifestVersion: Int,
        override val timestamp: Long = System.currentTimeMillis()
    ) : SystemEvent()

    data class BootFailed(
        val step: String,
        val reason: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : SystemEvent()

    data class RecoveryCompleted(
        val reconciliationStatus: String,  // ReconciliationResult.name
        val expiredSessions: Int,
        val readyForActive: Boolean,
        override val timestamp: Long = System.currentTimeMillis()
    ) : SystemEvent()

    data class BrokerStateChanged(
        val fromState: String,   // BrokerState.name
        val toState: String,     // BrokerState.name
        val manifestVersion: Int?,
        val reason: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : SystemEvent()
}

/**
 * Channel for durable system lifecycle events.
 * Returns true if durably acknowledged. Best-effort in practice — a failure
 * to emit a lifecycle event does not block the operation that caused it.
 */
interface SystemEventChannel {
    fun emit(event: SystemEvent): Boolean
}
