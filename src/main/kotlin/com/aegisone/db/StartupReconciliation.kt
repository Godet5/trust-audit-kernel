package com.aegisone.db

import java.sql.Connection

/**
 * Startup reconciliation — entry point stub.
 *
 * Implements the 6-step sequence from receiptDurabilitySpec-v1.md §7.1.
 * The ExecutionCoordinator MUST NOT transition to ACTIVE until run() returns.
 *
 * Steps 1–2 and 5 have real SQL implementations against the Phase 0 schema.
 * Steps 3–4 are stubs: their real implementations depend on RECEIPT_SUMMARY_CHANNEL
 * and per-session sequence tables that are not yet backed. The stubs document
 * the contract and boundary so the TODO is honest about what is missing.
 *
 * Crash window coverage per receiptDurabilitySpec-v1.md §6:
 *   W1 (orphaned PENDING, no execution)   → step 2: mark FAILED
 *   W2 (execution without receipt)        → not detectable here without external state; left to human review
 *   W3 (summary gap after receipt write)  → step 3: regenerate (stub)
 */
object StartupReconciliation {

    enum class ReconciliationResult {
        /** No anomalies found; coordinator may go ACTIVE. */
        CLEAN,
        /** Orphaned PENDING records found and marked FAILED; coordinator may go ACTIVE. */
        REPAIRED,
        /**
         * Unresolved UNAUDITED_IRREVERSIBLE records exist.
         * Coordinator MUST NOT go ACTIVE — human review gate required.
         */
        UNRESOLVED_FAILURES
    }

    /**
     * Run all reconciliation steps against [conn].
     * Returns [ReconciliationResult] indicating whether the coordinator may proceed.
     */
    fun run(conn: Connection): ReconciliationResult {

        // Step 1: Identify PENDING records in AUDIT_FAILURE_CHANNEL that have no
        //         corresponding ActionReceipt in RECEIPT_CHANNEL. These represent
        //         the W1 crash window: the system wrote a PENDING record but never
        //         completed execution or wrote a full receipt.
        val orphanedIds = queryOrphanedPending(conn)

        // Step 2: For each orphaned PENDING, execution did not occur.
        //         Write AuditRecord.Failed and commit. The coordinator will
        //         not re-attempt these actions.
        val repairedCount = markOrphanedAsFailed(conn, orphanedIds)

        // Step 3: Find receipts with no summary in RECEIPT_SUMMARY_CHANNEL (W3 window).
        //         These are benign — regenerate from full receipt on startup.
        // TODO: implement when RECEIPT_SUMMARY_CHANNEL is backed by a real table.
        //       Query: SELECT receipt_id FROM receipts WHERE receipt_id NOT IN (SELECT receipt_id FROM receipt_summaries)
        //       Action: re-emit summary record for each gap; commit.

        // Step 4: Verify sequence integrity across (agent_id, session_id) pairs.
        //         Gaps indicate a missed write or out-of-order processing.
        // TODO: implement when per-session sequence tables exist.
        //       Query: detect non-contiguous sequence_number values in receipts per session.
        //       Action: emit ConflictAlert for each gap; do not block ACTIVE unless irreversible.

        // Step 5: Check for unresolved UNAUDITED_IRREVERSIBLE records.
        //         Unresolved = present in audit_failure_records with no paired human-review gate.
        val unresolvedIrreversible = hasUnresolvedIrreversible(conn)

        // Step 6: Coordinator transitions based on result.
        return when {
            unresolvedIrreversible -> ReconciliationResult.UNRESOLVED_FAILURES
            repairedCount > 0      -> ReconciliationResult.REPAIRED
            else                   -> ReconciliationResult.CLEAN
        }
    }

    // --- private SQL helpers ---

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
