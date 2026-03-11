package com.aegisone.execution

import com.aegisone.receipt.ActionReceiptStatus
import com.aegisone.receipt.Receipt
import com.aegisone.receipt.ReceiptChannel
import java.util.UUID


/**
 * Owns the action lifecycle from capability validation through result delivery.
 * No module may execute a governed action by calling an executor directly —
 * all execution passes through the coordinator.
 *
 * The coordinator enforces I-3 structurally.
 * Currently implemented:
 *   step_0: live registry check (closes G-5) — if agentRegistry is wired, the
 *           requesting agent must be currently registered; deregistered agents
 *           are denied with AGENT_NOT_REGISTERED and a PolicyViolation receipt.
 *           No PENDING is written for a denied agent.
 *   step_1: PENDING record durably written to AUDIT_FAILURE_CHANNEL before execution
 *   step_2: executor called only after step_1 acknowledged
 *   step_3: full ActionReceipt written to RECEIPT_CHANNEL after execution;
 *           on write failure: reversible → reverse + FAILED; irreversible → I3-T3
 *   step_5: result delivered to ResultSink only after step_3 acknowledged (I3-T4)
 * Pending:
 *   step_4: summary written (best-effort) — I3-T5
 *
 * [agentRegistry] is optional. When null, the registry check is skipped and
 * existing behavior is preserved. This maintains backward compatibility for
 * all callers that do not wire a registry.
 *
 * Traces to: I-3, G-5 (authority-continuity closure)
 * Source: implementationMap-trust-and-audit-v1.md §3
 */
class ExecutionCoordinator(
    private val auditFailureChannel: AuditFailureChannel,
    private val receiptChannel: ReceiptChannel,
    private val executor: ActionExecutor,
    private val reversibilityRegistry: ReversibilityRegistry = IrreversibleByDefault,
    private val conflictChannel: ConflictChannel = SilentConflictChannel,
    private val resultSink: ResultSink = NoOpResultSink,
    private val sequenceProvider: SequenceProvider = MonotonicSequenceProvider(),
    private val agentRegistry: AgentRegistry? = null
) {
    private var lastAcceptedSequence = 0

    fun execute(request: ActionRequest): CoordinatorResult {
        // Step 0: Live registry check (G-5 closure).
        // If a registry is wired, the requesting agent must be currently registered.
        // This is checked before PENDING is written — a denied agent produces no
        // PENDING record and the executor is never called.
        if (agentRegistry != null && agentRegistry.slotOf(request.agentId) == null) {
            receiptChannel.write(Receipt.PolicyViolation(
                violation = "AGENT_NOT_REGISTERED",
                detail    = "Agent '${request.agentId}' attempted to execute " +
                            "'${request.capabilityName}' but is not currently registered; " +
                            "authority revoked at deregistration time"
            ))
            return CoordinatorResult.AGENT_NOT_REGISTERED
        }

        val receiptId = UUID.randomUUID().toString()
        val seq = sequenceProvider.next()

        // Detect sequence gap or reset: sequence must strictly increase.
        // If it doesn't, emit a SEQUENCE_GAP_DETECTED conflict alert and proceed —
        // a reset attempt is not grounds for cancellation; action is still logged.
        if (seq <= lastAcceptedSequence) {
            conflictChannel.alert(ConflictAlert(
                type = "SEQUENCE_GAP_DETECTED",
                detail = "Sequence decreased: expected > $lastAcceptedSequence, got $seq; " +
                         "possible reset attempt in session for agent ${request.agentId}",
                receiptId = receiptId
            ))
        }
        lastAcceptedSequence = seq

        // Step 1: Write PENDING to AUDIT_FAILURE_CHANNEL.
        // If this fails, cancel immediately — no external effect.
        val pendingAcknowledged = auditFailureChannel.write(
            AuditRecord.Pending(
                receiptId = receiptId,
                capabilityName = request.capabilityName,
                agentId = request.agentId,
                sessionId = request.sessionId,
                sequenceNumber = seq
            )
        )
        if (!pendingAcknowledged) {
            return CoordinatorResult.CANCELLED
        }

        // Step 2: Execute.
        val success = executor.execute(request)

        // Step 3: Write ActionReceipt to RECEIPT_CHANNEL.
        // This write must be acknowledged before result delivery.
        val receiptStatus = if (success) ActionReceiptStatus.SUCCESS else ActionReceiptStatus.FAILED
        val receiptAcknowledged = receiptChannel.write(
            Receipt.ActionReceipt(
                receiptId = receiptId,
                status = receiptStatus,
                capabilityName = request.capabilityName,
                agentId = request.agentId,
                sessionId = request.sessionId,
                sequenceNumber = seq
            )
        )

        if (!receiptAcknowledged) {
            return handleReceiptWriteFailure(request, receiptId, success)
        }

        // Step 5: deliver result — only after receipt acknowledgment (step_3).
        // This is the single call site for resultSink.deliver(); no other path reaches here.
        val finalResult = if (success) CoordinatorResult.SUCCESS else CoordinatorResult.FAILED
        resultSink.deliver(request, finalResult)
        return finalResult
    }

    private fun handleReceiptWriteFailure(
        request: ActionRequest,
        receiptId: String,
        executionSucceeded: Boolean
    ): CoordinatorResult {
        if (!executionSucceeded) {
            // Execution already failed; receipt write failure doesn't change the outcome.
            // Write a FAILED audit record and return FAILED.
            auditFailureChannel.write(AuditRecord.Failed(
                receiptId = receiptId,
                reason = "RECEIPT_WRITE_FAILED_AFTER_EXECUTION_FAILURE"
            ))
            return CoordinatorResult.FAILED
        }

        // Execution succeeded but receipt write failed.
        return if (reversibilityRegistry.isReversible(request.capabilityName)) {
            // Reversible: undo the action, record the failure, return FAILED.
            reversibilityRegistry.reverse(request)
            auditFailureChannel.write(AuditRecord.Failed(
                receiptId = receiptId,
                reason = "RECEIPT_WRITE_FAILED_ACTION_REVERSED"
            ))
            CoordinatorResult.FAILED
        } else {
            // Irreversible: write UNAUDITED_IRREVERSIBLE, emit conflict alert, no result delivered.
            auditFailureChannel.write(AuditRecord.UnauditedIrreversible(
                receiptId = receiptId,
                detail = "Irreversible action completed but receipt write failed; " +
                         "action cannot be undone"
            ))
            conflictChannel.alert(ConflictAlert(
                type = "UNAUDITED_IRREVERSIBLE_ACTION",
                detail = "Action ${request.capabilityName} completed with no durable receipt; " +
                         "agent ${request.agentId}, session ${request.sessionId}",
                receiptId = receiptId
            ))
            CoordinatorResult.UNAUDITED_IRREVERSIBLE
        }
    }
}

/** Safe default: all actions are irreversible unless explicitly registered otherwise. */
private object IrreversibleByDefault : ReversibilityRegistry {
    override fun isReversible(capabilityName: String) = false
    override fun reverse(request: ActionRequest) = false
}

/** No-op conflict channel used when no conflict channel is wired (test/default). */
private object SilentConflictChannel : ConflictChannel {
    override fun alert(alert: ConflictAlert) = true
}

/** No-op result sink used when no sink is wired (existing tests, default). */
private object NoOpResultSink : ResultSink {
    override fun deliver(request: ActionRequest, result: CoordinatorResult) = Unit
}

/** Default sequence provider: simple monotonic counter. Thread-safety not guaranteed. */
private class MonotonicSequenceProvider : SequenceProvider {
    private var counter = 0
    override fun next(): Int = ++counter
}
