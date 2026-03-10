package com.aegisone.execution

/**
 * Test double for ActionExecutor. Records whether execute() was called.
 * Used to prove no external effect occurred when the coordinator should have cancelled.
 */
class TrackingExecutor(private val succeeds: Boolean = true) : ActionExecutor {
    var wasExecuted: Boolean = false
        private set

    var callCount: Int = 0
        private set

    override fun execute(request: ActionRequest): Boolean {
        wasExecuted = true
        callCount++
        return succeeds
    }

    fun reset() {
        wasExecuted = false
        callCount = 0
    }
}
