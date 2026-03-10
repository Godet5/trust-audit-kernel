package com.aegisone.invariants

import com.aegisone.execution.ConflictAlert
import com.aegisone.execution.ConflictChannel

/**
 * In-memory conflict channel for Phase 0 harness tests.
 * Collects alerts for post-test assertion.
 */
class TestConflictChannel : ConflictChannel {
    val alerts = mutableListOf<ConflictAlert>()

    override fun alert(alert: ConflictAlert): Boolean {
        alerts.add(alert)
        return true
    }

    fun clear() { alerts.clear() }
}
