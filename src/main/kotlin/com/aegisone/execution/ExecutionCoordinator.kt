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
 *   step_0+1 (atomic): live registry check + PENDING write are fused into a single
 *           registry-locked unit when agentRegistry is wired (closes G-5a). The
 *           agent must be registered at the exact moment PENDING is written — no
 *           concurrent deregister() can interleave between the check and the write.
 *           Denied agents receive AGENT_NOT_REGISTERED + PolicyViolation; no PENDING.
 *   step_1: PENDING record durably written to AUDIT_FAILURE_CHANNEL before execution
 *   step_2: executor called only after step_1 acknowledged
 *   step_3: full ActionReceipt written to RECEIPT_CHANNEL after execution;
 *           on write failure: routes by executor.crashClass —
 *             ATOMIC        → FAILED (executor claims no-escape semantics)
 *             COMPENSATABLE → outcomeRegistry.compensate(); success → FAILED;
 *                             failure → UNAUDITED_IRREVERSIBLE + conflict alert
 *             INDETERMINATE → UNAUDITED_IRREVERSIBLE + conflict alert (default)
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
    private val outcomeRegistry: ExecutionOutcomeRegistry = NoCompensationRegistry,
    private val conflictChannel: ConflictChannel,
    private val resultSink: ResultSink = NoOpResultSink,
    private val sequenceProvider: SequenceProvider = MonotonicSequenceProvider(),
    private val agentRegistry: AgentRegistry? = null
) {
    private var lastAcceptedSequence = 0

    fun execute(request: ActionRequest): CoordinatorResult {
        val receiptId = UUID.randomUUID().toString()
        val seq = sequenceProvider.next()

        // Detect sequence gap or reset: sequence must strictly increase.
        if (seq <= lastAcceptedSequence) {
            conflictChannel.alert(ConflictAlert(
                type = "SEQUENCE_GAP_DETECTED",
                detail = "Sequence decreased: expected > $lastAcceptedSequence, got $seq; " +
                         "possible reset attempt in session for agent ${request.agentId}",
                receiptId = receiptId
            ))
        }
        lastAcceptedSequence = seq

        // Steps 0+1 (atomic): registration check fused with PENDING write.
        //
        // When agentRegistry is wired, checkAndBegin() holds the registry lock
        // while executing the block — a concurrent deregister() cannot interleave
        // between the live-registration check and the PENDING write (G-5a closure).
        //
        // When agentRegistry is null, behavior is unchanged: PENDING is written
        // unconditionally (backward-compatible for callers that do not wire a registry).
        val pendingAcknowledged: Boolean
        if (agentRegistry != null) {
            val result = agentRegistry.checkAndBegin(request.agentId) {
                auditFailureChannel.write(
                    AuditRecord.Pending(
                        receiptId      = receiptId,
                        capabilityName = request.capabilityName,
                        agentId        = request.agentId,
                        sessionId      = request.sessionId,
                        sequenceNumber = seq
                    )
                )
            }
            if (result == null) {
                // Agent was not registered at the time of the atomic check.
                receiptChannel.write(Receipt.PolicyViolation(
                    violation = "AGENT_NOT_REGISTERED",
                    detail    = "Agent '${request.agentId}' attempted to execute " +
                                "'${request.capabilityName}' but is not currently registered; " +
                                "authority revoked at deregistration time"
                ))
                return CoordinatorResult.AGENT_NOT_REGISTERED
            }
            pendingAcknowledged = result
        } else {
            // No registry wired — write PENDING unconditionally.
            pendingAcknowledged = auditFailureChannel.write(
                AuditRecord.Pending(
                    receiptId      = receiptId,
                    capabilityName = request.capabilityName,
                    agentId        = request.agentId,
                    sessionId      = request.sessionId,
                    sequenceNumber = seq
                )
            )
        }

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
            // Execution failed; receipt write failure does not change the outcome.
            auditFailureChannel.write(AuditRecord.Failed(
                receiptId = receiptId,
                reason = "RECEIPT_WRITE_FAILED_AFTER_EXECUTION_FAILURE"
            ))
            return CoordinatorResult.FAILED
        }

        // Execution succeeded but receipt write failed.
        // Route on the executor's declared crash semantics.
        return when (executor.crashClass) {
            CrashClass.ATOMIC -> {
                // Executor claims all-or-nothing semantics. A missing receipt means
                // the action did not escape. Classify conservatively as FAILED.
                auditFailureChannel.write(AuditRecord.Failed(
                    receiptId = receiptId,
                    reason = "RECEIPT_WRITE_FAILED_ATOMIC_EXECUTOR"
                ))
                CoordinatorResult.FAILED
            }
            CrashClass.COMPENSATABLE -> {
                // Side effects may have occurred. Attempt compensation.
                val compensated = outcomeRegistry.compensate(request)
                if (compensated) {
                    auditFailureChannel.write(AuditRecord.Failed(
                        receiptId = receiptId,
                        reason = "RECEIPT_WRITE_FAILED_ACTION_COMPENSATED"
                    ))
                    CoordinatorResult.FAILED
                } else {
                    // Compensation failed — downgrade to INDETERMINATE.
                    recordUnauditedIrreversible(
                        receiptId = receiptId,
                        request = request,
                        detail = "Compensatable action completed but receipt write failed " +
                                 "and compensation failed; external state unknown"
                    )
                    CoordinatorResult.UNAUDITED_IRREVERSIBLE
                }
            }
            CrashClass.INDETERMINATE -> {
                // Side effects may have escaped with no recovery path.
                recordUnauditedIrreversible(
                    receiptId = receiptId,
                    request = request,
                    detail = "Indeterminate action completed but receipt write failed; " +
                             "cannot determine whether side effects escaped"
                )
                CoordinatorResult.UNAUDITED_IRREVERSIBLE
            }
        }
    }

    private fun recordUnauditedIrreversible(
        receiptId: String,
        request: ActionRequest,
        detail: String
    ) {
        auditFailureChannel.write(AuditRecord.UnauditedIrreversible(
            receiptId = receiptId,
            detail = detail
        ))
        conflictChannel.alert(ConflictAlert(
            type = "UNAUDITED_IRREVERSIBLE_ACTION",
            detail = "Action ${request.capabilityName} completed with no durable receipt; " +
                     "agent ${request.agentId}, session ${request.sessionId}",
            receiptId = receiptId
        ))
    }
}

/**
 * Safe default: no compensation handlers are registered.
 * A COMPENSATABLE executor with no registry wired will have compensation return false,
 * escalating to UNAUDITED_IRREVERSIBLE. This prevents silent swallowing of unknown outcomes.
 */
private object NoCompensationRegistry : ExecutionOutcomeRegistry {
    override fun compensate(request: ActionRequest) = false
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
