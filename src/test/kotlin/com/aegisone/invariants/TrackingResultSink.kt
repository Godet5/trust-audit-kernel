package com.aegisone.invariants

import com.aegisone.execution.ActionRequest
import com.aegisone.execution.CoordinatorResult
import com.aegisone.execution.ResultSink

/**
 * Test double for ResultSink.
 * Counts delivery calls — used in I3-T4 to verify delivery has exactly one call site,
 * inside the ack-confirmed path.
 */
class TrackingResultSink : ResultSink {
    var deliveryCount: Int = 0
        private set

    override fun deliver(request: ActionRequest, result: CoordinatorResult) {
        deliveryCount++
    }
}
