package com.aegisone.invariants

import com.aegisone.execution.ActionRequest
import com.aegisone.execution.ExecutionOutcomeRegistry

/**
 * Test double for ExecutionOutcomeRegistry.
 * Tracks compensation call count. Configurable success/failure response.
 */
class TestExecutionOutcomeRegistry(
    private var compensationSucceeds: Boolean = true
) : ExecutionOutcomeRegistry {

    var compensateCallCount: Int = 0
        private set

    /** Configure whether the next compensate() call should succeed. */
    fun willCompensate(succeed: Boolean) {
        compensationSucceeds = succeed
    }

    override fun compensate(request: ActionRequest): Boolean {
        compensateCallCount++
        return compensationSucceeds
    }

    fun reset() {
        compensateCallCount = 0
    }
}
