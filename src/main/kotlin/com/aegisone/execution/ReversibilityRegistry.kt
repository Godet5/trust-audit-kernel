package com.aegisone.execution

/**
 * Maps capability actions to their reversibility classification and reverse handler.
 * The registry is system-defined; agents cannot override classifications.
 *
 * Irreversible by default: any action touching external systems
 * (EXECUTOR_COMMS send, EXECUTOR_COMMERCE submit/pay, any export action).
 * Reversible: file writes (before session end), calendar edits (before sync),
 * note edits (before session end).
 *
 * Source: implementationMap-trust-and-audit-v1.md §3.3
 */
interface ReversibilityRegistry {
    fun isReversible(capabilityName: String): Boolean

    /**
     * Attempt to reverse the action. Returns true if reversal succeeded.
     * Only called when the action was reversible and a reversal is required.
     */
    fun reverse(request: ActionRequest): Boolean
}
