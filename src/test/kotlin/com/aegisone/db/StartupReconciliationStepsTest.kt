package com.aegisone.db

import com.aegisone.execution.AuditRecord
import com.aegisone.execution.ConflictAlert
import com.aegisone.execution.ConflictChannel
import com.aegisone.receipt.ActionReceiptStatus
import com.aegisone.receipt.Receipt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for StartupReconciliation steps 3 and 4.
 *
 * Step 3 — summary gap regeneration (W3 crash window):
 *   SR-5: ActionReceipt with no summary is regenerated; REPAIRED returned
 *   SR-6: ActionReceipt with existing summary is not re-regenerated; CLEAN returned
 *   SR-7: Regenerated summary is durably written (readable from DB)
 *   SR-8: SUMMARY_REGENERATED event logged to audit_failure_records
 *
 * Step 4 — sequence continuity (advisory; does not block ACTIVE):
 *   SR-9:  Contiguous sequences produce no ConflictAlert
 *   SR-10: Gap in sequence_numbers emits a ConflictAlert
 *   SR-11: Step 4 alert does not change ReconciliationResult (CLEAN stays CLEAN)
 *   SR-12: Multiple sessions: only the gapped session triggers an alert
 *
 * The original SR-1..4 tests in SQLiteChannelIntegrationTest remain untouched.
 */
class StartupReconciliationStepsTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var dbPath: String
    private lateinit var conn: Connection

    @BeforeEach
    fun setup() {
        dbPath = File(tempDir, "recon-test.db").absolutePath
        conn = SQLiteBootstrap.openAndInitialize(dbPath)
    }

    @AfterEach
    fun teardown() {
        runCatching { conn.close() }
    }

    // --- helpers ---

    private fun writeActionReceipt(
        receiptId: String,
        agentId: String = "agent-a",
        sessionId: String = "sess-1",
        sequenceNumber: Int = 1
    ) {
        SQLiteReceiptChannel(conn).write(Receipt.ActionReceipt(
            receiptId       = receiptId,
            status          = ActionReceiptStatus.SUCCESS,
            capabilityName  = "FILE_READ",
            agentId         = agentId,
            sessionId       = sessionId,
            sequenceNumber  = sequenceNumber
        ))
    }

    private fun writePending(
        receiptId: String,
        agentId: String = "agent-a",
        sessionId: String = "sess-1",
        sequenceNumber: Int = 1
    ) {
        SQLiteAuditFailureChannel(conn).write(AuditRecord.Pending(
            receiptId      = receiptId,
            capabilityName = "FILE_READ",
            agentId        = agentId,
            sessionId      = sessionId,
            sequenceNumber = sequenceNumber
        ))
    }

    private fun countRows(table: String, whereClause: String = "1=1"): Int =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM $table WHERE $whereClause").use { rs ->
                rs.next(); rs.getInt(1)
            }
        }

    /** Capturing ConflictChannel for test assertions. */
    private class CapturingConflictChannel : ConflictChannel {
        val alerts = mutableListOf<ConflictAlert>()
        override fun alert(alert: ConflictAlert): Boolean {
            alerts.add(alert)
            return true
        }
    }

    // === Step 3: Summary gap regeneration ===

    @Test
    fun `SR-5 ActionReceipt with no summary triggers regeneration — REPAIRED`() {
        writeActionReceipt("rcpt-001")

        val result = StartupReconciliation.run(conn)
        assertEquals(StartupReconciliation.ReconciliationResult.REPAIRED, result,
            "Missing summary for an ActionReceipt must produce REPAIRED")
    }

    @Test
    fun `SR-6 ActionReceipt with existing summary is not regenerated — CLEAN`() {
        writeActionReceipt("rcpt-002")

        // First run regenerates the summary
        StartupReconciliation.run(conn)

        // Second run: summary exists; nothing to regenerate
        val result = StartupReconciliation.run(conn)
        assertEquals(StartupReconciliation.ReconciliationResult.CLEAN, result,
            "No new gaps after first regeneration must produce CLEAN")
    }

    @Test
    fun `SR-7 Regenerated summary is durably written to receipt_summaries`() {
        writeActionReceipt("rcpt-003", agentId = "agent-x", sessionId = "sess-x")

        StartupReconciliation.run(conn)

        val count = countRows("receipt_summaries",
            "receipt_id = 'rcpt-003' AND regenerated = 1")
        assertEquals(1, count, "Regenerated summary must be present in receipt_summaries")
    }

    @Test
    fun `SR-8 SummaryRegenerated event logged to audit_failure_records`() {
        writeActionReceipt("rcpt-004")

        StartupReconciliation.run(conn)

        val count = countRows("audit_failure_records",
            "record_type = 'SummaryRegenerated' AND receipt_id = 'rcpt-004'")
        assertEquals(1, count, "SummaryRegenerated event must be logged for each regenerated summary")
    }

    // === Step 4: Sequence continuity ===

    @Test
    fun `SR-9 Contiguous sequences produce no ConflictAlert`() {
        writeActionReceipt("rcpt-010", sequenceNumber = 1)
        writeActionReceipt("rcpt-011", sequenceNumber = 2)
        writeActionReceipt("rcpt-012", sequenceNumber = 3)

        val channel = CapturingConflictChannel()
        StartupReconciliation.run(conn, channel)

        assertTrue(channel.alerts.isEmpty(),
            "Contiguous sequence 1-2-3 must not trigger any ConflictAlert")
    }

    @Test
    fun `SR-10 Gap in sequence_numbers emits SEQUENCE_GAP_DETECTED alert`() {
        // Write sequences 1, 2, 4 — missing 3
        writeActionReceipt("rcpt-020", sequenceNumber = 1)
        writeActionReceipt("rcpt-021", sequenceNumber = 2)
        writeActionReceipt("rcpt-022", sequenceNumber = 4)

        val channel = CapturingConflictChannel()
        StartupReconciliation.run(conn, channel)

        assertEquals(1, channel.alerts.size, "One gap in one session must produce exactly one alert")
        assertEquals("SEQUENCE_GAP_DETECTED", channel.alerts[0].type)
        assertTrue(channel.alerts[0].detail.contains("agent-a"),
            "Alert detail must identify the affected agent")
    }

    @Test
    fun `SR-11 Step 4 alert does not change ReconciliationResult — CLEAN stays CLEAN`() {
        // Write a gapped sequence with no other anomalies
        writeActionReceipt("rcpt-030", sequenceNumber = 1)
        writeActionReceipt("rcpt-031", sequenceNumber = 3)  // gap: missing 2

        // Flush the summary gap first (so those don't produce REPAIRED)
        StartupReconciliation.run(conn)

        // Second run: no orphaned pending, no summary gaps; only a sequence gap
        val channel = CapturingConflictChannel()
        val result = StartupReconciliation.run(conn, channel)

        assertEquals(StartupReconciliation.ReconciliationResult.CLEAN, result,
            "Sequence gap alert must not change CLEAN to any other result")
        assertEquals(1, channel.alerts.size,
            "Sequence gap must still be reported even though result is CLEAN")
    }

    @Test
    fun `SR-12 Gapped session emits alert — clean session does not`() {
        // Session A: contiguous sequences 1, 2
        writeActionReceipt("rcpt-040", agentId = "agent-a", sessionId = "sess-A", sequenceNumber = 1)
        writeActionReceipt("rcpt-041", agentId = "agent-a", sessionId = "sess-A", sequenceNumber = 2)

        // Session B: gap — sequences 1, 3 (missing 2)
        writeActionReceipt("rcpt-042", agentId = "agent-b", sessionId = "sess-B", sequenceNumber = 1)
        writeActionReceipt("rcpt-043", agentId = "agent-b", sessionId = "sess-B", sequenceNumber = 3)

        // Flush summary gaps from writes above
        StartupReconciliation.run(conn)

        val channel = CapturingConflictChannel()
        StartupReconciliation.run(conn, channel)

        assertEquals(1, channel.alerts.size,
            "Only the gapped session (B) must produce an alert; clean session (A) must not")
        assertTrue(channel.alerts[0].detail.contains("agent-b"),
            "Alert must identify agent-b as the agent with the gap")
    }
}
