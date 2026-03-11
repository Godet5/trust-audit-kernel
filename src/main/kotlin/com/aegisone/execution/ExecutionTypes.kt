package com.aegisone.execution

/**
 * Core types for the ExecutionCoordinator.
 *
 * Source: implementationMap-trust-and-audit-v1.md §3
 */

/** An action request submitted to the ExecutionCoordinator. */
data class ActionRequest(
    val capabilityName: String,
    val agentId: String,
    val sessionId: String
)

/** The result returned to the requesting agent after coordinator processing. */
enum class CoordinatorResult {
    /** Action completed; receipt acknowledged. */
    SUCCESS,
    /** Action cancelled before execution — audit channel unavailable at step_1. */
    CANCELLED,
    /** Action executed but failed, or receipt write failed on reversible action. */
    FAILED,
    /**
     * Irreversible action completed but receipt write failed.
     * This result MUST NOT be delivered to the requesting agent.
     * The system cannot confirm or deny completion. The audit trail documents
     * the breach via UNAUDITED_IRREVERSIBLE in AUDIT_FAILURE_CHANNEL and
     * an alert in CONFLICT_CHANNEL. No retry.
     *
     * Source: implementationMap-trust-and-audit-v1.md §3.2 step_3
     */
    UNAUDITED_IRREVERSIBLE,
    /**
     * Action blocked because the requesting agent is not currently registered
     * in the AgentRegistry. The executor is never called.
     *
     * This closes G-5: authority revocation is complete — a deregistered agent
     * cannot execute with a stale capability, because the coordinator checks live
     * registry state before writing PENDING.
     *
     * A PolicyViolation receipt is written to RECEIPT_CHANNEL identifying the denial.
     */
    AGENT_NOT_REGISTERED
}

/**
 * Declares how a missing receipt after execution should be classified.
 * This is the executor author's contract — classification is declared per-executor,
 * not inferred from capability names at reconciliation time.
 *
 * ATOMIC        — execution is all-or-nothing by construction; a missing receipt
 *                 means the action did not escape. Safe to classify as FAILED.
 * COMPENSATABLE — side effects may occur, but a compensating action exists that
 *                 can undo them. Coordinator calls outcomeRegistry.compensate();
 *                 if compensation fails, escalates to UNAUDITED_IRREVERSIBLE.
 * INDETERMINATE — side effects may have escaped and cannot be compensated.
 *                 Missing receipt → UNAUDITED_IRREVERSIBLE + conflict alert.
 *
 * INDETERMINATE is the safe default: executors that do not declare their crash
 * semantics are treated as potentially having escaped state.
 *
 * Source: receiptDurabilitySpec-v1.md §W2; agentPolicyEngine-v2.1.md §7.6
 */
enum class CrashClass {
    ATOMIC,
    COMPENSATABLE,
    INDETERMINATE
}

/**
 * Executor interface. Called only by the ExecutionCoordinator after PENDING record
 * is durably written. Must not be called directly by any other module.
 *
 * [crashClass] declares the crash semantics for this executor. Defaults to
 * INDETERMINATE — the safe assumption that side effects may have escaped.
 * Executor authors MUST override [crashClass] when the implementation can
 * provide stronger guarantees (ATOMIC) or a compensating path (COMPENSATABLE).
 *
 * Source: implementationMap-trust-and-audit-v1.md §3.1
 */
interface ActionExecutor {
    /** Returns true if execution succeeded. */
    fun execute(request: ActionRequest): Boolean

    /**
     * Crash semantics for this executor. Defaults to INDETERMINATE.
     * Override to declare stronger guarantees.
     */
    val crashClass: CrashClass get() = CrashClass.INDETERMINATE
}

/**
 * Audit failure channel. Receives PENDING records before execution.
 * If this write fails, the action is cancelled with no external effect.
 *
 * This is a separate channel from ReceiptChannel (RECEIPT_CHANNEL).
 * In production this maps to Zone D append-only storage.
 *
 * Source: implementationMap-trust-and-audit-v1.md §3.2 step_1
 */
interface AuditFailureChannel {
    /** Write a record. Returns true if durably acknowledged. */
    fun write(record: AuditRecord): Boolean
}

/**
 * Conflict channel. Receives alerts on irreversible audit breaches.
 * Separate from RECEIPT_CHANNEL and AUDIT_FAILURE_CHANNEL.
 * Alerts here require supervisory attention — normal operation is not possible
 * while unresolved UNAUDITED_IRREVERSIBLE events exist.
 *
 * Source: implementationMap-trust-and-audit-v1.md §3.2 step_3, §5
 */
interface ConflictChannel {
    fun alert(alert: ConflictAlert): Boolean
}

data class ConflictAlert(
    val type: String,
    val detail: String,
    val receiptId: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Sequence provider. Issues monotonically increasing sequence numbers per session.
 * The coordinator uses this to stamp each PENDING record. An external provider
 * allows tests to inject resets and verify the coordinator detects them.
 *
 * Source: implementationMap-trust-and-audit-v1.md §3.2, §6 I3-T5/I3-T6
 */
interface SequenceProvider {
    /** Returns the next sequence number. Must be strictly greater than the previous value. */
    fun next(): Int
}

/**
 * Result sink. The coordinator delivers results to requesting agents through this interface.
 * There is exactly one call site inside the coordinator — after receipt acknowledgment.
 * This structural constraint is what I3-T4 verifies.
 *
 * Source: implementationMap-trust-and-audit-v1.md §3.2 step_5
 */
interface ResultSink {
    fun deliver(request: ActionRequest, result: CoordinatorResult)
}

/** Records written to the AUDIT_FAILURE_CHANNEL. */
sealed class AuditRecord {
    data class Pending(
        val receiptId: String,
        val capabilityName: String,
        val agentId: String,
        val sessionId: String,
        val sequenceNumber: Int,
        val timestamp: Long = System.currentTimeMillis()
    ) : AuditRecord()

    data class Failed(
        val receiptId: String,
        val reason: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : AuditRecord()

    data class UnauditedIrreversible(
        val receiptId: String,
        val detail: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : AuditRecord()
}
