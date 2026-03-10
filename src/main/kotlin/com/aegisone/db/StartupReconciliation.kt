package com.aegisone.db

import com.aegisone.execution.ConflictAlert
import com.aegisone.execution.ConflictChannel
import java.sql.Connection
import java.util.UUID

/**
 * Startup reconciliation — full implementation.
 *
 * Implements the 6-step sequence from receiptDurabilitySpec-v1.md §7.1.
 * The ExecutionCoordinator MUST NOT transition to ACTIVE until run() returns.
 *
 * Crash window coverage per receiptDurabilitySpec-v1.md §5:
 *   W1 (orphaned PENDING, no execution)        → step 2: mark FAILED
 *   W2 (execution without receipt)             → not distinguishable from W1 at startup;
 *                                                 both classified as FAILED (see note below)
 *   W3 (summary gap after full receipt write)  → step 3: regenerate summary + log event
 *
 * W1/W2 note: The spec (§5.2) classifies W2 as UNAUDITED_INDETERMINATE, but startup
 * reconciliation cannot distinguish W1 (no execution) from W2 (execution without receipt)
 * using only local DB state — the spec explicitly prohibits external-system inspection.
 * Phase 0 applies the W1 treatment (FAILED) to all orphaned PENDING records. The full
 * IndeterminateExecutionPolicy (reversal attempt + UNAUDITED_INDETERMINATE) is deferred
 * to the reversibility registry integration, which Phase 0 does not yet back.
 *
 * [conflictChannel] is optional. If null, step 4 sequence alerts are suppressed.
 * This preserves backward compatibility for callers that do not yet wire a channel.
 */
object StartupReconciliation {

    enum class ReconciliationResult {
        /** No anomalies found; coordinator may go ACTIVE. */
        CLEAN,
        /**
         * Orphaned PENDING records marked FAILED and/or summary gaps regenerated.
         * Repair work was done; coordinator may go ACTIVE.
         */
        REPAIRED,
        /**
         * Unresolved UNAUDITED_IRREVERSIBLE records exist.
         * Coordinator MUST NOT go ACTIVE — human review gate required.
         */
        UNRESOLVED_FAILURES
    }

    /**
     * Run all reconciliation steps against [conn].
     *
     * [conflictChannel] receives sequence gap alerts from step 4. Pass null to
     * suppress alerts (e.g., in tests that do not exercise step 4).
     *
     * Returns [ReconciliationResult] indicating whether the coordinator may proceed.
     */
    fun run(conn: Connection, conflictChannel: ConflictChannel? = null): ReconciliationResult {

        // Step 1: Identify PENDING records in AUDIT_FAILURE_CHANNEL that have no
        //         corresponding ActionReceipt in RECEIPT_CHANNEL (W1/W2 crash window).
        val orphanedIds = queryOrphanedPending(conn)

        // Step 2: Mark orphaned PENDING records as FAILED. Execution did not produce
        //         a durable receipt; these actions will not be re-attempted.
        val repairedFromPending = markOrphanedAsFailed(conn, orphanedIds)

        // Step 3: Scan RECEIPT_CHANNEL for ActionReceipts with no matching summary
        //         in RECEIPT_SUMMARY_CHANNEL (W3 crash window — benign).
        //         Regenerate the summary from the authoritative full receipt.
        //         Log a SummaryRegenerated event to AUDIT_FAILURE_CHANNEL.
        //         Source: receiptDurabilitySpec-v1.md §5.3, SummaryGapRecoveryPolicy
        val repairedFromSummaries = regenerateSummaryGaps(conn)

        // Step 4: Verify sequence continuity across (agent_id, session_id) pairs.
        //         Gaps indicate a missed write or out-of-order delivery.
        //         Emit ConflictAlerts — do not block ACTIVE.
        //         Source: receiptDurabilitySpec-v1.md §7.1 step_5
        checkSequenceContinuity(conn, conflictChannel)

        // Step 5: Check for unresolved UNAUDITED_IRREVERSIBLE records.
        //         Unresolved = present in audit_failure_records; these require
        //         human review before the coordinator may proceed.
        val unresolvedIrreversible = hasUnresolvedIrreversible(conn)

        // Step 6: Coordinator transitions based on reconciliation outcome.
        return when {
            unresolvedIrreversible                           -> ReconciliationResult.UNRESOLVED_FAILURES
            repairedFromPending > 0 || repairedFromSummaries > 0 -> ReconciliationResult.REPAIRED
            else                                             -> ReconciliationResult.CLEAN
        }
    }

    // --- Step 1/2 helpers (orphaned PENDING) ---

    private fun queryOrphanedPending(conn: Connection): List<String> {
        val sql = """
            SELECT afr.receipt_id
            FROM audit_failure_records afr
            WHERE afr.record_type = 'Pending'
              AND NOT EXISTS (
                  SELECT 1 FROM receipts r
                  WHERE r.receipt_id = afr.receipt_id
                    AND r.receipt_type = 'ActionReceipt'
              )
        """.trimIndent()
        val ids = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) ids.add(rs.getString("receipt_id"))
            }
        }
        return ids
    }

    private fun markOrphanedAsFailed(conn: Connection, ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val sql = """
            INSERT INTO audit_failure_records (
                record_type, receipt_id, timestamp_ms,
                capability_name, agent_id, session_id, sequence_number,
                reason, detail, inserted_at_ms
            ) VALUES ('Failed', ?, ?, NULL, NULL, NULL, NULL, 'W1_ORPHANED_PENDING', NULL, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            for (id in ids) {
                ps.setString(1, id)
                ps.setLong(2, now)
                ps.setLong(3, now)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.commit()
        return ids.size
    }

    // --- Step 3 helper (summary gap regeneration / W3) ---

    private data class ReceiptSummaryRow(
        val receiptId: String,
        val agentId: String?,
        val sessionId: String?,
        val status: String?,
        val capabilityName: String?,
        val timestampMs: Long
    )

    private fun regenerateSummaryGaps(conn: Connection): Int {
        // Find ActionReceipts with no matching entry in receipt_summaries.
        val sql = """
            SELECT r.receipt_id, r.agent_id, r.session_id, r.status,
                   r.capability_name, r.timestamp_ms
            FROM receipts r
            WHERE r.receipt_type = 'ActionReceipt'
              AND NOT EXISTS (
                  SELECT 1 FROM receipt_summaries s
                  WHERE s.receipt_id = r.receipt_id
              )
        """.trimIndent()

        val gaps = mutableListOf<ReceiptSummaryRow>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    gaps.add(ReceiptSummaryRow(
                        receiptId       = rs.getString("receipt_id"),
                        agentId         = rs.getString("agent_id"),
                        sessionId       = rs.getString("session_id"),
                        status          = rs.getString("status"),
                        capabilityName  = rs.getString("capability_name"),
                        timestampMs     = rs.getLong("timestamp_ms")
                    ))
                }
            }
        }

        if (gaps.isEmpty()) return 0

        val now = System.currentTimeMillis()

        // Insert regenerated summaries. INSERT OR IGNORE avoids duplicates if another
        // recovery path already wrote the summary since we queried.
        val insertSummary = """
            INSERT OR IGNORE INTO receipt_summaries (
                receipt_id, agent_id, session_id, status, capability_name,
                receipt_timestamp_ms, inserted_at_ms, regenerated
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 1)
        """.trimIndent()
        conn.prepareStatement(insertSummary).use { ps ->
            for (gap in gaps) {
                ps.setString(1, gap.receiptId)
                ps.setString(2, gap.agentId ?: "")
                ps.setString(3, gap.sessionId ?: "")
                ps.setString(4, gap.status ?: "UNKNOWN")
                ps.setString(5, gap.capabilityName ?: "")
                ps.setLong(6, gap.timestampMs)
                ps.setLong(7, now)
                ps.addBatch()
            }
            ps.executeBatch()
        }

        // Log each regeneration event to AUDIT_FAILURE_CHANNEL.
        // record_type 'SummaryRegenerated' is a reconciliation-internal write;
        // it does not correspond to an AuditRecord sealed class subtype because
        // it is never written through the AuditFailureChannel interface.
        val logRegen = """
            INSERT INTO audit_failure_records (
                record_type, receipt_id, timestamp_ms,
                capability_name, agent_id, session_id, sequence_number,
                reason, detail, inserted_at_ms
            ) VALUES ('SummaryRegenerated', ?, ?, NULL, NULL, NULL, NULL,
                      'W3_SUMMARY_GAP', NULL, ?)
        """.trimIndent()
        conn.prepareStatement(logRegen).use { ps ->
            for (gap in gaps) {
                ps.setString(1, gap.receiptId)
                ps.setLong(2, now)
                ps.setLong(3, now)
                ps.addBatch()
            }
            ps.executeBatch()
        }

        conn.commit()
        return gaps.size
    }

    // --- Step 4 helper (sequence continuity) ---

    private fun checkSequenceContinuity(conn: Connection, conflictChannel: ConflictChannel?) {
        if (conflictChannel == null) return

        // A gap exists when the count of distinct sequence numbers in a session
        // does not equal (max - min + 1). This detects missing sequence values
        // regardless of the starting number.
        val sql = """
            SELECT agent_id, session_id,
                   COUNT(DISTINCT sequence_number)                    AS actual_count,
                   MAX(sequence_number) - MIN(sequence_number) + 1   AS expected_count
            FROM receipts
            WHERE receipt_type    = 'ActionReceipt'
              AND agent_id        IS NOT NULL
              AND session_id      IS NOT NULL
              AND sequence_number IS NOT NULL
            GROUP BY agent_id, session_id
            HAVING actual_count != expected_count
        """.trimIndent()

        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    val agentId      = rs.getString("agent_id")
                    val sessionId    = rs.getString("session_id")
                    val actualCount  = rs.getInt("actual_count")
                    val expectedCount = rs.getInt("expected_count")
                    conflictChannel.alert(ConflictAlert(
                        type      = "SEQUENCE_GAP_DETECTED",
                        detail    = "agent=$agentId session=$sessionId: " +
                                    "expected $expectedCount sequence numbers, found $actualCount",
                        receiptId = "reconciliation-${UUID.randomUUID()}"
                    ))
                }
            }
        }
        // Step 4 does not commit — it only reads and emits alerts.
        // ConflictChannel availability failure is not propagated here;
        // sequence alerts are advisory and do not block coordinator startup.
    }

    // --- Step 5 helper (UNAUDITED_IRREVERSIBLE check) ---

    private fun hasUnresolvedIrreversible(conn: Connection): Boolean {
        val sql = """
            SELECT COUNT(*) FROM audit_failure_records
            WHERE record_type = 'UnauditedIrreversible'
        """.trimIndent()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                return rs.next() && rs.getInt(1) > 0
            }
        }
    }
}
