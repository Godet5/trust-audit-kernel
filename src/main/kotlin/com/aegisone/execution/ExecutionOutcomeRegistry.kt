package com.aegisone.execution

/**
 * Provides compensation logic for COMPENSATABLE executors whose receipt write failed.
 *
 * The coordinator calls [compensate] only when:
 *   1. executor.crashClass == COMPENSATABLE
 *   2. Execution succeeded
 *   3. Receipt write failed
 *
 * If [compensate] returns true, the action is classified as FAILED (the side effect
 * was undone). If [compensate] returns false, the coordinator escalates to
 * UNAUDITED_IRREVERSIBLE — compensation failed, external state is unknown.
 *
 * This registry is separate from the executor contract because compensation logic
 * is a recovery-path concern, not an execution concern. Two executors for the same
 * capability may share a compensator, or each may have its own.
 *
 * The coordinator's built-in default [NoCompensationRegistry] returns false for all
 * capabilities, causing any unwired COMPENSATABLE executor to escalate to
 * UNAUDITED_IRREVERSIBLE. This is the safe default: if you declare COMPENSATABLE
 * but do not wire a compensator, the system treats the outcome as unresolved.
 *
 * Source: receiptDurabilitySpec-v1.md §W2; agentPolicyEngine-v2.1.md §7.6
 */
interface ExecutionOutcomeRegistry {
    /**
     * Attempt to compensate for a COMPENSATABLE action whose receipt write failed.
     * Returns true if compensation succeeded (side effect was undone).
     * Returns false if compensation failed (escalate to UNAUDITED_IRREVERSIBLE).
     */
    fun compensate(request: ActionRequest): Boolean
}
