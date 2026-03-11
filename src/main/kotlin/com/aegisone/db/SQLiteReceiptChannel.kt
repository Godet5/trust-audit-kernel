package com.aegisone.db

import com.aegisone.receipt.Receipt
import com.aegisone.receipt.ReceiptChannel
import java.sql.Types

/**
 * SQLite-backed ReceiptChannel. Replaces the in-memory test double as the
 * real acknowledgment boundary for the RECEIPT_CHANNEL.
 *
 * Durability guarantee: write() returns true if and only if conn.commit()
 * succeeds without exception. A false return means the row was not durably
 * written; the coordinator treats this as a receipt write failure and applies
 * the appropriate failure path per implementationMap §3.2.
 *
 * WAL journal mode and synchronous=FULL must already be set on the connection
 * (applied by SQLiteBootstrap.openAndInitialize). This class does not re-apply
 * them — callers that bypass SQLiteBootstrap lose the guarantee.
 *
 * Thread safety: synchronized on [shared]. All objects sharing the same
 * SharedConnection use the same monitor — no cross-object lock gap.
 *
 * Source: receiptDurabilitySpec-v1.md §4, §7
 */
class SQLiteReceiptChannel(private val shared: SharedConnection) : ReceiptChannel {

    override fun write(receipt: Receipt): Boolean = synchronized(shared) {
        try {
            shared.conn.autoCommit = false
            insertReceipt(receipt)
            shared.conn.commit()
            true
        } catch (e: Exception) {
            runCatching { shared.conn.rollback() }
            false
        }
    }

    private fun insertReceipt(receipt: Receipt) {
        val now = System.currentTimeMillis()
        val sql = """
            INSERT INTO receipts (
                receipt_type, timestamp_ms,
                receipt_id, status, capability_name, agent_id, session_id, sequence_number,
                violation, step, anomaly_type, artifact_id, from_state, to_state, changed_by,
                detail, reason, inserted_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        shared.conn.prepareStatement(sql).use { ps ->
            // Positional helpers — null for unused columns
            fun str(pos: Int, v: String?) = if (v != null) ps.setString(pos, v) else ps.setNull(pos, Types.VARCHAR)
            fun int(pos: Int, v: Int?) = if (v != null) ps.setInt(pos, v) else ps.setNull(pos, Types.INTEGER)

            when (receipt) {
                is Receipt.ActionReceipt -> {
                    ps.setString(1, "ActionReceipt")
                    ps.setLong(2, receipt.timestamp)
                    str(3, receipt.receiptId)
                    str(4, receipt.status.name)
                    str(5, receipt.capabilityName)
                    str(6, receipt.agentId)
                    str(7, receipt.sessionId)
                    int(8, receipt.sequenceNumber)
                    str(9, null); str(10, null); str(11, null)
                    str(12, null); str(13, null); str(14, null); str(15, null)
                    str(16, null); str(17, null)
                }
                is Receipt.PolicyViolation -> {
                    ps.setString(1, "PolicyViolation")
                    ps.setLong(2, receipt.timestamp)
                    str(3, null); str(4, null); str(5, null); str(6, null); str(7, null)
                    int(8, null)
                    str(9, receipt.violation)
                    str(10, null); str(11, null); str(12, null); str(13, null)
                    str(14, null); str(15, null)
                    str(16, receipt.detail)
                    str(17, null)
                }
                is Receipt.ManifestFailure -> {
                    ps.setString(1, "ManifestFailure")
                    ps.setLong(2, receipt.timestamp)
                    str(3, null); str(4, null); str(5, null); str(6, null); str(7, null)
                    int(8, null); str(9, null)
                    str(10, receipt.step)
                    str(11, null); str(12, null); str(13, null); str(14, null); str(15, null)
                    str(16, null)
                    str(17, receipt.reason)
                }
                is Receipt.Anomaly -> {
                    ps.setString(1, "Anomaly")
                    ps.setLong(2, receipt.timestamp)
                    str(3, null); str(4, null); str(5, null); str(6, null); str(7, null)
                    int(8, null); str(9, null); str(10, null)
                    str(11, receipt.type)
                    str(12, null); str(13, null); str(14, null); str(15, null)
                    str(16, receipt.detail)
                    str(17, null)
                }
                is Receipt.ProposalStatusReceipt -> {
                    ps.setString(1, "ProposalStatusReceipt")
                    ps.setLong(2, receipt.timestamp)
                    str(3, null); str(4, null); str(5, null); str(6, null); str(7, null)
                    int(8, null); str(9, null); str(10, null); str(11, null)
                    str(12, receipt.artifactId)
                    str(13, receipt.fromState)
                    str(14, receipt.toState)
                    str(15, receipt.changedBy)
                    str(16, null); str(17, null)
                }
            }
            ps.setLong(18, now)
            ps.executeUpdate()
        }
    }
}
