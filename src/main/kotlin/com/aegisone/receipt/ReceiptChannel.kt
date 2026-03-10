package com.aegisone.receipt

/**
 * Receipt channel interface. Durable write target for audit records.
 * Implementations: in-memory for Phase 0 harness, persistent for production.
 *
 * Durability semantics are a pre-implementation blocker (see implementationMap §8).
 * For Phase 0, "acknowledged" means the write call returned without exception.
 *
 * Source: implementationMap-trust-and-audit-v1.md §3, §5
 */
interface ReceiptChannel {
    /**
     * Write a receipt. Returns true if durably acknowledged.
     * Throws nothing — returns false on write failure.
     */
    fun write(receipt: Receipt): Boolean
}

/** Base type for all receipts in the system. */
sealed class Receipt {
    abstract val timestamp: Long

    data class PolicyViolation(
        override val timestamp: Long = System.currentTimeMillis(),
        val violation: String,
        val detail: String
    ) : Receipt()

    data class ManifestFailure(
        override val timestamp: Long = System.currentTimeMillis(),
        val step: String,
        val reason: String
    ) : Receipt()

    data class Anomaly(
        override val timestamp: Long = System.currentTimeMillis(),
        val type: String,
        val detail: String
    ) : Receipt()

    data class ProposalStatusReceipt(
        override val timestamp: Long = System.currentTimeMillis(),
        val artifactId: String,
        val fromState: String,
        val toState: String,
        val changedBy: String
    ) : Receipt()

    data class ActionReceipt(
        override val timestamp: Long = System.currentTimeMillis(),
        val receiptId: String,
        val status: ActionReceiptStatus,
        val capabilityName: String,
        val agentId: String,
        val sessionId: String,
        val sequenceNumber: Int
    ) : Receipt()
}

enum class ActionReceiptStatus {
    SUCCESS,
    FAILED
}
