package com.aegisone.invariants

import com.aegisone.execution.ActionRequest
import com.aegisone.execution.ReversibilityRegistry

/**
 * Test double for ReversibilityRegistry.
 * Explicit registrations required — nothing is reversible by default.
 * Tracks reversal calls for assertion in I3-T2 tests.
 */
class TestReversibilityRegistry : ReversibilityRegistry {
    private val entries = mutableMapOf<String, Boolean>()  // capabilityName → reversible
    var reverseCallCount: Int = 0
        private set

    /** Explicitly register a capability's reversibility. */
    fun register(capabilityName: String, reversible: Boolean) {
        entries[capabilityName] = reversible
    }

    override fun isReversible(capabilityName: String): Boolean = entries[capabilityName] ?: false

    override fun reverse(request: ActionRequest): Boolean {
        reverseCallCount++
        return true
    }

    fun reset() { reverseCallCount = 0 }
}
