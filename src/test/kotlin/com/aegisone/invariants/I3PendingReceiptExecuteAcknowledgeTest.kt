// INVARIANT TEST — DO NOT MODIFY WITHOUT SPEC CHANGE (implementationMap-trust-and-audit-v1.md §6)
package com.aegisone.invariants

import com.aegisone.execution.ActionRequest
import com.aegisone.execution.CoordinatorResult
import com.aegisone.execution.CrashClass
import com.aegisone.execution.ExecutionCoordinator
import com.aegisone.execution.TrackingExecutor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * I-3: Every governed action requiring a receipt is not complete until the required
 * receipt set is durably acknowledged, or the action is rolled back and marked failed.
 * Actions execute only after a PENDING record is durably written.
 * Results are delivered to the requesting agent only after full receipt acknowledgment.
 *
 * Source: implementationMap-trust-and-audit-v1.md §3, §6 I-3 tests
 */
class I3PendingReceiptExecuteAcknowledgeTest {

    // ---------------------------------------------------------------
    // I3-T1: AUDIT_FAILURE_CHANNEL unavailable at step_1
    //        → Action not executed; no external effect; result: CANCELLED
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I3-T1: Action cancelled when AUDIT_FAILURE_CHANNEL unavailable before execution")
    fun actionCancelledWhenAuditChannelUnavailable() {
        val executor = TrackingExecutor(succeeds = true)
        val receiptChannel = TestReceiptChannel()
        val auditChannel = TestAuditFailureChannel(available = false)

        val coordinator = ExecutionCoordinator(
            auditFailureChannel = auditChannel,
            receiptChannel = receiptChannel,
            executor = executor,
            conflictChannel = TestConflictChannel()
        )

        val request = ActionRequest(
            capabilityName = "FILE_READ",
            agentId = "agent-01",
            sessionId = "session-01"
        )

        val result = coordinator.execute(request)

        // Must be cancelled
        assertEquals(CoordinatorResult.CANCELLED, result,
            "I-3 violation: action not cancelled when AUDIT_FAILURE_CHANNEL unavailable")

        // Executor must never have been called — no external effect
        assertFalse(executor.wasExecuted,
            "I-3 violation: executor was called despite unavailable audit channel; external effect occurred")

        // No receipt written to RECEIPT_CHANNEL — the action did not execute
        assertEquals(0, receiptChannel.receipts.size,
            "No receipts should be written to RECEIPT_CHANNEL when action is cancelled at step_1")
    }

    // ---------------------------------------------------------------
    // I3-T2: Executor succeeds; RECEIPT_CHANNEL write fails; action is COMPENSATABLE
    //        → Compensation called; FAILED audit record written; result: FAILED
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I3-T2: Compensatable action compensated and result FAILED when receipt write fails")
    fun compensatableActionCompensatedOnReceiptWriteFailure() {
        val auditChannel = TestAuditFailureChannel(available = true)
        val receiptChannel = TestReceiptChannel(available = false)  // step_3 write will fail
        val executor = TrackingExecutor(succeeds = true, crashClass = CrashClass.COMPENSATABLE)
        val outcomeRegistry = TestExecutionOutcomeRegistry(compensationSucceeds = true)

        val coordinator = ExecutionCoordinator(
            auditFailureChannel = auditChannel,
            receiptChannel = receiptChannel,
            executor = executor,
            outcomeRegistry = outcomeRegistry,
            conflictChannel = TestConflictChannel()
        )

        val request = ActionRequest(
            capabilityName = "FILE_WRITE",
            agentId = "agent-01",
            sessionId = "session-01"
        )

        val result = coordinator.execute(request)

        // Executor was called — the action was attempted before the receipt failure
        assertTrue(executor.wasExecuted,
            "Executor must have been called before receipt write failure")

        // Result must be FAILED — the action was compensated
        assertEquals(CoordinatorResult.FAILED, result,
            "I-3 violation: result must be FAILED when receipt write fails on compensatable action")

        // Compensation must have been invoked
        assertEquals(1, outcomeRegistry.compensateCallCount,
            "Compensation must be called exactly once")

        // PENDING record must exist (was written at step_1)
        assertEquals(1, auditChannel.pendingRecords().size,
            "PENDING record must have been written at step_1")

        // FAILED audit record must exist — proving the compensation was recorded
        assertEquals(1, auditChannel.failedRecords().size,
            "FAILED audit record must be written after compensation")
    }

    // ---------------------------------------------------------------
    // I3-T3: Executor succeeds; RECEIPT_CHANNEL write fails; action is INDETERMINATE
    //        → UNAUDITED_IRREVERSIBLE record written; CONFLICT_CHANNEL alert; no result delivered
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I3-T3: Indeterminate action produces UNAUDITED_IRREVERSIBLE with no result delivered")
    fun indeterminateActionUnauditedOnReceiptWriteFailure() {
        val auditChannel = TestAuditFailureChannel(available = true)
        val receiptChannel = TestReceiptChannel(available = false)  // step_3 write will fail
        val conflictChannel = TestConflictChannel()
        // TrackingExecutor defaults to CrashClass.INDETERMINATE — external message sent, no recovery
        val executor = TrackingExecutor(succeeds = true, crashClass = CrashClass.INDETERMINATE)

        val coordinator = ExecutionCoordinator(
            auditFailureChannel = auditChannel,
            receiptChannel = receiptChannel,
            executor = executor,
            conflictChannel = conflictChannel
        )

        val request = ActionRequest(
            capabilityName = "COMMS_SEND",
            agentId = "agent-01",
            sessionId = "session-01"
        )

        val result = coordinator.execute(request)

        // Executor was called — the action completed in the real world
        assertTrue(executor.wasExecuted, "Executor must have been called")

        // Result must signal no delivery — UNAUDITED_IRREVERSIBLE is not delivered to agent
        assertEquals(CoordinatorResult.UNAUDITED_IRREVERSIBLE, result,
            "I-3 violation: wrong result for indeterminate action with receipt failure")

        // UNAUDITED_IRREVERSIBLE audit record must exist
        assertEquals(1, auditChannel.unauditedIrreversibleRecords().size,
            "UNAUDITED_IRREVERSIBLE record must be written to audit channel")

        // CONFLICT_CHANNEL alert must have been emitted
        assertEquals(1, conflictChannel.alerts.size,
            "CONFLICT_CHANNEL alert must be emitted on indeterminate audit failure")

        // No ActionReceipt in RECEIPT_CHANNEL — the write failed
        assertEquals(0, receiptChannel.receipts.filterIsInstance<com.aegisone.receipt.Receipt.ActionReceipt>().size,
            "No ActionReceipt should exist in RECEIPT_CHANNEL when write failed")
    }

    // ---------------------------------------------------------------
    // I3-T4: Result delivery structurally gated on receipt acknowledgment
    //        → deliver_result() has a single call site, inside the ack-confirmed path.
    //          Verified by showing delivery occurs exactly once on success, and zero
    //          times when receipt write fails — proving no alternate delivery path exists.
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I3-T4: Result delivery gated on receipt acknowledgment — no alternate delivery path")
    fun resultDeliveryGatedOnReceiptAcknowledgment() {
        // Part A: Receipt acknowledged → delivery occurs exactly once
        val receiptChannel = TestReceiptChannel(available = true)
        val resultSink = TrackingResultSink()

        val coordinator = ExecutionCoordinator(
            auditFailureChannel = TestAuditFailureChannel(available = true),
            receiptChannel = receiptChannel,
            executor = TrackingExecutor(succeeds = true),
            conflictChannel = TestConflictChannel(),
            resultSink = resultSink
        )

        coordinator.execute(ActionRequest("FILE_READ", "agent-01", "session-01"))

        assertEquals(1, resultSink.deliveryCount,
            "I-3 violation: result not delivered on success path")
        // Receipt must exist in channel — delivery happened after ack, not before
        assertEquals(1, receiptChannel.receipts.filterIsInstance<com.aegisone.receipt.Receipt.ActionReceipt>().size,
            "ActionReceipt must be present in channel at point of delivery")

        // Part B: Receipt write fails → delivery must NOT occur (compensatable action)
        val resultSinkB = TrackingResultSink()

        val coordinatorB = ExecutionCoordinator(
            auditFailureChannel = TestAuditFailureChannel(available = true),
            receiptChannel = TestReceiptChannel(available = false),
            executor = TrackingExecutor(succeeds = true, crashClass = CrashClass.COMPENSATABLE),
            outcomeRegistry = TestExecutionOutcomeRegistry(compensationSucceeds = true),
            conflictChannel = TestConflictChannel(),
            resultSink = resultSinkB
        )

        coordinatorB.execute(ActionRequest("FILE_READ", "agent-01", "session-01"))

        assertEquals(0, resultSinkB.deliveryCount,
            "I-3 violation: result delivered before receipt was acknowledged (compensatable path)")

        // Part C: Receipt write fails → delivery must NOT occur (irreversible action)
        val resultSinkC = TrackingResultSink()

        val coordinatorC = ExecutionCoordinator(
            auditFailureChannel = TestAuditFailureChannel(available = true),
            receiptChannel = TestReceiptChannel(available = false),
            executor = TrackingExecutor(succeeds = true),
            conflictChannel = TestConflictChannel(),
            resultSink = resultSinkC
        )

        coordinatorC.execute(ActionRequest("COMMS_SEND", "agent-01", "session-01"))

        assertEquals(0, resultSinkC.deliveryCount,
            "I-3 violation: result delivered before receipt was acknowledged (irreversible path)")
    }

    // ---------------------------------------------------------------
    // I3-T5: Session with N actions → sequence_numbers 1..N with no gaps
    //        → PENDING records in AUDIT_FAILURE_CHANNEL carry a strictly
    //          monotonic sequence starting at 1 for the session. No gaps,
    //          no resets, no skips.
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I3-T5: N consecutive actions produce sequence_numbers 1..N with no gaps")
    fun sequenceNumbersAreContiguousAcrossSession() {
        val auditChannel = TestAuditFailureChannel(available = true)
        val coordinator = ExecutionCoordinator(
            auditFailureChannel = auditChannel,
            receiptChannel = TestReceiptChannel(available = true),
            executor = TrackingExecutor(succeeds = true),
            conflictChannel = TestConflictChannel()
        )

        val n = 5
        repeat(n) { i ->
            coordinator.execute(ActionRequest(
                capabilityName = "FILE_READ",
                agentId = "agent-01",
                sessionId = "session-01"
            ))
        }

        val pending = auditChannel.pendingRecords()

        // Exactly N PENDING records — one per action
        assertEquals(n, pending.size,
            "Expected $n PENDING records, one per action")

        // Sequence numbers must be exactly 1..N in order, no gaps
        val sequenceNumbers = pending.map { it.sequenceNumber }
        assertEquals((1..n).toList(), sequenceNumbers,
            "I-3 violation: sequence_numbers are not contiguous 1..$n — gaps or resets detected")
    }

    // ---------------------------------------------------------------
    // I3-T6: Mid-session sequence reset attempt
    //        → coordinator detects sequence decrease; emits anomaly alert;
    //          action is still logged with the gap noted (receipt still written,
    //          result still delivered — a reset attempt is not grounds for cancellation).
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I3-T6: Mid-session sequence reset attempt triggers anomaly; action still logged")
    fun sequenceResetDetectedAsGapWithAnomalyAlert() {
        val auditChannel = TestAuditFailureChannel(available = true)
        val conflictChannel = TestConflictChannel()
        val sequenceProvider = MutableSequenceProvider()

        val coordinator = ExecutionCoordinator(
            auditFailureChannel = auditChannel,
            receiptChannel = TestReceiptChannel(available = true),
            executor = TrackingExecutor(succeeds = true),
            conflictChannel = conflictChannel,
            sequenceProvider = sequenceProvider
        )

        // Execute 3 actions normally — sequence should be 1, 2, 3
        repeat(3) {
            coordinator.execute(ActionRequest("FILE_READ", "agent-01", "session-01"))
        }

        // Inject reset: force sequence provider back to 1
        sequenceProvider.reset(to = 1)

        // Execute one more action — provider will issue sequence 1 again (a reset/decrease)
        coordinator.execute(ActionRequest("FILE_READ", "agent-01", "session-01"))

        // Action must still have been logged — receipt still written, not cancelled
        assertEquals(4, auditChannel.pendingRecords().size,
            "I-3 violation: action not logged after sequence reset; should still be written")

        // The reset action's sequence number must be flagged in a gap anomaly alert
        assertEquals(1, conflictChannel.alerts.filter { it.type == "SEQUENCE_GAP_DETECTED" }.size,
            "I-3 violation: no SEQUENCE_GAP_DETECTED anomaly alert on sequence reset")
    }
}
