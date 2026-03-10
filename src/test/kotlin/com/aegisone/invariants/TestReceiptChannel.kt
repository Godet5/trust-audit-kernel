package com.aegisone.invariants

import com.aegisone.receipt.Receipt
import com.aegisone.receipt.ReceiptChannel

/**
 * In-memory receipt channel for harness tests.
 * Records all receipts for post-test assertion.
 * Configurable to simulate write failures.
 */
class TestReceiptChannel(
    private val available: Boolean = true
) : ReceiptChannel {

    val receipts = mutableListOf<Receipt>()

    override fun write(receipt: Receipt): Boolean {
        if (!available) return false
        receipts.add(receipt)
        return true
    }

    fun clear() { receipts.clear() }

    /** Filter receipts by type. */
    inline fun <reified T : Receipt> receiptsOfType(): List<T> =
        receipts.filterIsInstance<T>()

    /** Find policy violations by violation code. */
    fun policyViolations(violation: String? = null): List<Receipt.PolicyViolation> =
        receiptsOfType<Receipt.PolicyViolation>().let { pvs ->
            if (violation != null) pvs.filter { it.violation == violation } else pvs
        }

    /** Find anomalies by type. */
    fun anomalies(type: String? = null): List<Receipt.Anomaly> =
        receiptsOfType<Receipt.Anomaly>().let { as_ ->
            if (type != null) as_.filter { it.type == type } else as_
        }
}
