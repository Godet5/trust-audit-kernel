package com.aegisone.db

import com.aegisone.execution.AuditFailureChannel
import com.aegisone.execution.AuditRecord
import java.sql.Connection
import java.sql.Types

/**
 * SQLite-backed AuditFailureChannel. Replaces the in-memory test double as the
 * real acknowledgment boundary for the AUDIT_FAILURE_CHANNEL.
 *
 * Maps to Zone D append-only storage in production. In Phase 0, a SQLite table
 * with WAL + FULL synchronous is the real boundary.
 *
 * Durability guarantee: write() returns true if and only if conn.commit()
 * succeeds without exception. A false return causes the coordinator to cancel
 * the action with no external effect (step_1 failure path, I-3).
 *
 * Same threading model as SQLiteReceiptChannel: @Synchronized, single connection.
 *
 * Source: receiptDurabilitySpec-v1.md §4; implementationMap §3.2 step_1
 */
class SQLiteAuditFailureChannel(private val conn: Connection) : AuditFailureChannel {

    @Synchronized
    override fun write(record: AuditRecord): Boolean {
        return try {
            conn.autoCommit = false
            insertRecord(record)
            conn.commit()
            true
        } catch (e: Exception) {
            runCatching { conn.rollback() }
            false
        }
    }

    private fun insertRecord(record: AuditRecord) {
        val now = System.currentTimeMillis()
        val sql = """
            INSERT INTO audit_failure_records (
                record_type, receipt_id, timestamp_ms,
                capability_name, agent_id, session_id, sequence_number,
                reason, detail, inserted_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        conn.prepareStatement(sql).use { ps ->
            fun str(pos: Int, v: String?) = if (v != null) ps.setString(pos, v) else ps.setNull(pos, Types.VARCHAR)
            fun int(pos: Int, v: Int?) = if (v != null) ps.setInt(pos, v) else ps.setNull(pos, Types.INTEGER)

            when (record) {
                is AuditRecord.Pending -> {
                    ps.setString(1, "Pending")
                    ps.setString(2, record.receiptId)
                    ps.setLong(3, record.timestamp)
                    str(4, record.capabilityName)
                    str(5, record.agentId)
                    str(6, record.sessionId)
                    int(7, record.sequenceNumber)
                    str(8, null)
                    str(9, null)
                }
                is AuditRecord.Failed -> {
                    ps.setString(1, "Failed")
                    ps.setString(2, record.receiptId)
                    ps.setLong(3, record.timestamp)
                    str(4, null); str(5, null); str(6, null)
                    int(7, null)
                    str(8, record.reason)
                    str(9, null)
                }
                is AuditRecord.UnauditedIrreversible -> {
                    ps.setString(1, "UnauditedIrreversible")
                    ps.setString(2, record.receiptId)
                    ps.setLong(3, record.timestamp)
                    str(4, null); str(5, null); str(6, null)
                    int(7, null); str(8, null)
                    str(9, record.detail)
                }
            }
            ps.setLong(10, now)
            ps.executeUpdate()
        }
    }
}
