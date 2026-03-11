package com.aegisone.execution

import com.aegisone.invariants.TestAuditFailureChannel
import com.aegisone.invariants.TestConflictChannel
import com.aegisone.invariants.TestExecutionOutcomeRegistry
import com.aegisone.invariants.TestReceiptChannel
import com.aegisone.invariants.TrackingResultSink
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Crash semantics routing in ExecutionCoordinator.
 *
 * Proves that a missing receipt after execution is classified according to
 * executor.crashClass — not inferred from capability names or registry defaults.
 * This is the Phase 1 readiness gate: every real executor must declare its crash
 * semantics before landing, and the coordinator must route correctly.
 *
 * CS-1: ATOMIC executor, receipt write failure → FAILED (no compensation, no alert)
 * CS-2: COMPENSATABLE executor, receipt write failure, compensation succeeds → FAILED
 * CS-3: COMPENSATABLE executor, receipt write failure, compensation fails → UNAUDITED_IRREVERSIBLE
 * CS-4: INDETERMINATE executor, receipt write failure → UNAUDITED_IRREVERSIBLE + alert
 * CS-5: Execution fails (INDETERMINATE) — receipt write also fails → FAILED regardless of class
 * CS-6: Default crashClass on anonymous executor is INDETERMINATE
 */
class CrashSemanticsTest {

    private fun makeRequest(capability: String = "CAP") =
        ActionRequest(capabilityName = capability, agentId = "agent-cs", sessionId = "sess-cs")

    private fun coordinator(
        executor: ActionExecutor,
        outcomeRegistry: ExecutionOutcomeRegistry = NoOpOutcomeRegistry,
        receiptAvailable: Boolean = false   // default: receipt write fails (the interesting path)
    ) = ExecutionCoordinator(
        auditFailureChannel = TestAuditFailureChannel(available = true),
        receiptChannel      = TestReceiptChannel(available = receiptAvailable),
        executor            = executor,
        outcomeRegistry     = outcomeRegistry,
        conflictChannel     = TestConflictChannel()
    )

    // CS-1: ATOMIC — receipt write failure → FAILED, no compensation, no alert

    @Test
    fun `CS-1 ATOMIC executor receipt write failure — FAILED no compensation no conflict alert`() {
        val executor      = TrackingExecutor(succeeds = true, crashClass = CrashClass.ATOMIC)
        val outcomeReg    = TestExecutionOutcomeRegistry()
        val conflictCh    = TestConflictChannel()
        val auditCh       = TestAuditFailureChannel(available = true)

        val result = ExecutionCoordinator(
            auditFailureChannel = auditCh,
            receiptChannel      = TestReceiptChannel(available = false),
            executor            = executor,
            outcomeRegistry     = outcomeReg,
            conflictChannel     = conflictCh
        ).execute(makeRequest())

        assertEquals(CoordinatorResult.FAILED, result,
            "ATOMIC executor must return FAILED when receipt write fails")
        assertEquals(0, outcomeReg.compensateCallCount,
            "No compensation must be attempted for ATOMIC executor")
        assertTrue(conflictCh.alerts.isEmpty(),
            "No conflict alert must be emitted for ATOMIC executor")
        // FAILED audit record must exist
        assertEquals(1, auditCh.failedRecords().size,
            "FAILED audit record must be written for ATOMIC path")
        assertTrue(auditCh.unauditedIrreversibleRecords().isEmpty(),
            "No UNAUDITED_IRREVERSIBLE record must be written for ATOMIC path")
    }

    // CS-2: COMPENSATABLE, compensation succeeds → FAILED

    @Test
    fun `CS-2 COMPENSATABLE executor receipt write failure compensation succeeds — FAILED`() {
        val executor   = TrackingExecutor(succeeds = true, crashClass = CrashClass.COMPENSATABLE)
        val outcomeReg = TestExecutionOutcomeRegistry(compensationSucceeds = true)
        val auditCh    = TestAuditFailureChannel(available = true)
        val conflictCh = TestConflictChannel()

        val result = ExecutionCoordinator(
            auditFailureChannel = auditCh,
            receiptChannel      = TestReceiptChannel(available = false),
            executor            = executor,
            outcomeRegistry     = outcomeReg,
            conflictChannel     = conflictCh
        ).execute(makeRequest())

        assertEquals(CoordinatorResult.FAILED, result,
            "COMPENSATABLE executor must return FAILED when compensation succeeds")
        assertEquals(1, outcomeReg.compensateCallCount,
            "Compensation must be attempted exactly once")
        assertTrue(conflictCh.alerts.isEmpty(),
            "No conflict alert when compensation succeeds")
        assertEquals(1, auditCh.failedRecords().size,
            "FAILED audit record must be written after compensation")
        assertTrue(auditCh.unauditedIrreversibleRecords().isEmpty(),
            "No UNAUDITED_IRREVERSIBLE record when compensation succeeds")
    }

    // CS-3: COMPENSATABLE, compensation fails → UNAUDITED_IRREVERSIBLE + alert

    @Test
    fun `CS-3 COMPENSATABLE executor receipt write failure compensation fails — UNAUDITED_IRREVERSIBLE`() {
        val executor   = TrackingExecutor(succeeds = true, crashClass = CrashClass.COMPENSATABLE)
        val outcomeReg = TestExecutionOutcomeRegistry(compensationSucceeds = false)
        val auditCh    = TestAuditFailureChannel(available = true)
        val conflictCh = TestConflictChannel()

        val result = ExecutionCoordinator(
            auditFailureChannel = auditCh,
            receiptChannel      = TestReceiptChannel(available = false),
            executor            = executor,
            outcomeRegistry     = outcomeReg,
            conflictChannel     = conflictCh
        ).execute(makeRequest())

        assertEquals(CoordinatorResult.UNAUDITED_IRREVERSIBLE, result,
            "COMPENSATABLE executor must escalate to UNAUDITED_IRREVERSIBLE when compensation fails")
        assertEquals(1, outcomeReg.compensateCallCount,
            "Compensation must be attempted exactly once before escalation")
        assertEquals(1, conflictCh.alerts.size,
            "Conflict alert must be emitted when compensation fails")
        assertEquals(1, auditCh.unauditedIrreversibleRecords().size,
            "UNAUDITED_IRREVERSIBLE record must be written on compensation failure")
        assertTrue(auditCh.failedRecords().isEmpty(),
            "No FAILED audit record when escalating to UNAUDITED_IRREVERSIBLE")
    }

    // CS-4: INDETERMINATE → UNAUDITED_IRREVERSIBLE + alert, no compensation attempted

    @Test
    fun `CS-4 INDETERMINATE executor receipt write failure — UNAUDITED_IRREVERSIBLE no compensation`() {
        val executor   = TrackingExecutor(succeeds = true, crashClass = CrashClass.INDETERMINATE)
        val outcomeReg = TestExecutionOutcomeRegistry()
        val auditCh    = TestAuditFailureChannel(available = true)
        val conflictCh = TestConflictChannel()

        val result = ExecutionCoordinator(
            auditFailureChannel = auditCh,
            receiptChannel      = TestReceiptChannel(available = false),
            executor            = executor,
            outcomeRegistry     = outcomeReg,
            conflictChannel     = conflictCh
        ).execute(makeRequest())

        assertEquals(CoordinatorResult.UNAUDITED_IRREVERSIBLE, result,
            "INDETERMINATE executor must return UNAUDITED_IRREVERSIBLE when receipt write fails")
        assertEquals(0, outcomeReg.compensateCallCount,
            "No compensation must be attempted for INDETERMINATE executor")
        assertEquals(1, conflictCh.alerts.size,
            "Conflict alert must be emitted for INDETERMINATE executor")
        assertEquals(1, auditCh.unauditedIrreversibleRecords().size,
            "UNAUDITED_IRREVERSIBLE record must be written")
    }

    // CS-5: Execution fails — crash class is irrelevant; result is always FAILED

    @Test
    fun `CS-5 execution fails receipt write also fails — FAILED regardless of crash class`() {
        val auditCh    = TestAuditFailureChannel(available = true)
        val outcomeReg = TestExecutionOutcomeRegistry()
        val conflictCh = TestConflictChannel()

        // All three crash classes should produce FAILED when execution itself fails
        for (crashClass in CrashClass.entries) {
            val executor = TrackingExecutor(succeeds = false, crashClass = crashClass)

            val result = ExecutionCoordinator(
                auditFailureChannel = auditCh,
                receiptChannel      = TestReceiptChannel(available = false),
                executor            = executor,
                outcomeRegistry     = outcomeReg,
                conflictChannel     = conflictCh
            ).execute(makeRequest())

            assertEquals(CoordinatorResult.FAILED, result,
                "Execution failure must always produce FAILED regardless of crashClass=$crashClass")
        }
        assertEquals(0, outcomeReg.compensateCallCount,
            "No compensation must be attempted when execution itself fails")
    }

    // CS-6: Anonymous executor with no crashClass override defaults to INDETERMINATE

    @Test
    fun `CS-6 anonymous executor with no crashClass override defaults to INDETERMINATE`() {
        val anon = object : ActionExecutor {
            override fun execute(request: ActionRequest) = true
            // No crashClass override — should default to INDETERMINATE
        }
        assertEquals(CrashClass.INDETERMINATE, anon.crashClass,
            "Default crashClass must be INDETERMINATE — safe assumption when not declared")
    }

    // Private no-op registry used when the test doesn't wire a compensator
    private object NoOpOutcomeRegistry : ExecutionOutcomeRegistry {
        override fun compensate(request: ActionRequest) = false
    }
}
