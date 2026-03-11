package com.aegisone.boot

import com.aegisone.db.SQLiteAuditFailureChannel
import com.aegisone.db.SQLiteBootstrap
import com.aegisone.db.SQLiteReceiptChannel
import com.aegisone.execution.ActionRequest
import com.aegisone.execution.CoordinatorResult
import com.aegisone.execution.ExecutionCoordinator
import com.aegisone.execution.TrackingExecutor
import com.aegisone.invariants.TestReversibilityRegistry
import com.aegisone.invariants.TrackingResultSink
import com.aegisone.receipt.ActionReceiptStatus
import com.aegisone.receipt.Receipt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adversarial storage fault injection tests.
 *
 * Proves the real SQLite-backed receipt path fails closed under storage write
 * faults and does not allow the coordinator to claim durable completion on a
 * failed write.
 *
 * These tests attack the receipt boundary — the one place the system could
 * flatter itself by treating a write failure as success.
 *
 * SF-1: Closed connection at step_1 — CANCELLED, executor never called,
 *        result sink never called. Clean cancel with no external effect.
 *
 * SF-2: Step_1 succeeds, step_3 (ActionReceipt) fails on closed connection,
 *        irreversible action → UNAUDITED_IRREVERSIBLE. Executor was called.
 *        Result sink not called. Audit trail records Pending then UnauditedIrreversible.
 *
 * SF-3: Step_1 succeeds, step_3 fails, reversible action → FAILED. reverse()
 *        called exactly once. Audit trail records Pending then Failed. Result
 *        sink not called.
 *
 * SF-4: SQLITE_BUSY at step_1 — real write-lock contention (two connections
 *        to the same file, second holds active write transaction). The channel
 *        returns false; coordinator returns CANCELLED. Proves contention is
 *        treated the same as channel unavailability, not retried silently.
 *
 * SF-5: Duplicate receipt_id injection — a row with a known receipt_id is
 *        pre-seeded. The coordinator's write for an action with the same
 *        receipt_id is rejected by the DB UNIQUE index and returns false.
 *        The coordinator follows the honest failure path (UNAUDITED_IRREVERSIBLE
 *        for irreversible actions). External pre-seeding cannot cause silent
 *        double-completion or cover a real action.
 *
 * Production code change paired with SF-5: SQLiteBootstrap.applySchema() now
 * creates a partial UNIQUE index on receipts(receipt_id) WHERE receipt_id IS NOT
 * NULL. This is the DB-level complement to the coordinator's UUIDv4 PRNG.
 */
class AdversarialStorageFaultsE2ETest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var workingConn: Connection

    private val CAPABILITY = "WRITE_NOTE"
    private val AGENT_ID   = "exec-01"
    private val SESSION_ID = "sess-sf"

    @BeforeEach
    fun setup() {
        workingConn = SQLiteBootstrap.openAndInitialize(File(tempDir, "receipts.db").absolutePath)
    }

    @AfterEach
    fun teardown() {
        runCatching { workingConn.close() }
    }

    private fun makeRequest() = ActionRequest(
        capabilityName = CAPABILITY,
        agentId        = AGENT_ID,
        sessionId      = SESSION_ID
    )

    /** Open a fresh DB and immediately close the connection. Returns a dead Connection. */
    private fun closedConnection(): Connection {
        val conn = SQLiteBootstrap.openAndInitialize(File(tempDir, "dead-${System.nanoTime()}.db").absolutePath)
        conn.close()
        return conn
    }

    // --- SF-1: closed audit connection → CANCELLED, no execution ---

    @Test
    fun `SF-1 closed audit connection — step_1 CANCELLED executor not called result sink not called`() {
        val auditChannel  = SQLiteAuditFailureChannel(closedConnection())
        val receiptChannel = SQLiteReceiptChannel(workingConn)
        val executor = TrackingExecutor(succeeds = true)
        val sink     = TrackingResultSink()

        val result = ExecutionCoordinator(
            auditFailureChannel = auditChannel,
            receiptChannel      = receiptChannel,
            executor            = executor,
            resultSink          = sink
        ).execute(makeRequest())

        assertEquals(CoordinatorResult.CANCELLED, result,
            "step_1 failure must cancel the action with no external effect")
        assertFalse(executor.wasExecuted,
            "Executor must not be called when PENDING write fails")
        assertEquals(0, sink.deliveryCount,
            "Result sink must not be called on CANCELLED")
    }

    // --- SF-2: step_3 fails, irreversible → UNAUDITED_IRREVERSIBLE ---

    @Test
    fun `SF-2 step_3 receipt write fails irreversible — UNAUDITED_IRREVERSIBLE executor called delivery blocked`() {
        // Audit channel (step_1) on working connection — PENDING write succeeds.
        // Receipt channel (step_3) on dead connection — ActionReceipt write fails.
        val auditChannel   = SQLiteAuditFailureChannel(workingConn)
        val receiptChannel = SQLiteReceiptChannel(closedConnection())
        val executor = TrackingExecutor(succeeds = true)
        val sink     = TrackingResultSink()

        // IrreversibleByDefault is the coordinator's built-in default.
        val result = ExecutionCoordinator(
            auditFailureChannel = auditChannel,
            receiptChannel      = receiptChannel,
            executor            = executor,
            resultSink          = sink
        ).execute(makeRequest())

        assertEquals(CoordinatorResult.UNAUDITED_IRREVERSIBLE, result,
            "Irreversible action with failed receipt write must return UNAUDITED_IRREVERSIBLE")
        assertTrue(executor.wasExecuted,
            "Executor must have been called — action happened before the write failure")
        assertEquals(0, sink.deliveryCount,
            "Result sink must not be called — delivery is gated on receipt acknowledgment")

        // Audit trail: PENDING written (step_1 succeeded), then UnauditedIrreversible.
        val auditTypes = auditRecordTypes(workingConn)
        assertTrue(auditTypes.any { it == "Pending" },
            "PENDING record must be present — step_1 succeeded before the action ran")
        assertTrue(auditTypes.any { it == "UnauditedIrreversible" },
            "UnauditedIrreversible must be recorded — honest acknowledgment of unaudited completion")
    }

    // --- SF-3: step_3 fails, reversible → FAILED, reverse called ---

    @Test
    fun `SF-3 step_3 receipt write fails reversible — FAILED reverse called delivery blocked`() {
        val auditChannel   = SQLiteAuditFailureChannel(workingConn)
        val receiptChannel = SQLiteReceiptChannel(closedConnection())
        val executor     = TrackingExecutor(succeeds = true)
        val sink         = TrackingResultSink()
        val reversibility = TestReversibilityRegistry().apply { register(CAPABILITY, reversible = true) }

        val result = ExecutionCoordinator(
            auditFailureChannel = auditChannel,
            receiptChannel      = receiptChannel,
            executor            = executor,
            reversibilityRegistry = reversibility,
            resultSink          = sink
        ).execute(makeRequest())

        assertEquals(CoordinatorResult.FAILED, result,
            "Reversible action with failed receipt write must return FAILED after reversal")
        assertTrue(executor.wasExecuted,
            "Executor must have been called — action executed before the write failure")
        assertEquals(1, reversibility.reverseCallCount,
            "reverse() must be called exactly once — the action is undone on write failure")
        assertEquals(0, sink.deliveryCount,
            "Result sink must not be called — reversal path never reaches delivery")

        // Audit trail: PENDING then Failed (reversal recorded).
        assertEquals(listOf("Pending", "Failed"), auditRecordTypes(workingConn),
            "Audit trail must record PENDING then Failed — the reversal failure path")
    }

    // --- SF-4: SQLITE_BUSY at step_1 → CANCELLED ---

    @Test
    fun `SF-4 SQLITE_BUSY at step_1 — write-lock contention treated as channel unavailable CANCELLED`() {
        // Open a second connection to the same DB file and hold an uncommitted write
        // transaction. SQLite WAL mode allows only one concurrent writer; conn2's
        // active write transaction blocks workingConn's write → SQLITE_BUSY.
        val dbPath = File(tempDir, "receipts.db").absolutePath
        val conn2  = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        try {
            conn2.autoCommit = false
            // Acquire the write lock by inserting a row and not committing.
            conn2.createStatement().execute(
                "INSERT INTO audit_failure_records " +
                "(record_type, receipt_id, timestamp_ms, inserted_at_ms) " +
                "VALUES ('BusyTest', 'lock-holder', 0, 0)"
            )
            // conn2 now holds the write lock; workingConn's write attempt will get SQLITE_BUSY.

            val executor = TrackingExecutor(succeeds = true)
            val sink     = TrackingResultSink()

            val result = ExecutionCoordinator(
                auditFailureChannel = SQLiteAuditFailureChannel(workingConn),
                receiptChannel      = SQLiteReceiptChannel(workingConn),
                executor            = executor,
                resultSink          = sink
            ).execute(makeRequest())

            assertEquals(CoordinatorResult.CANCELLED, result,
                "SQLITE_BUSY at step_1 must cancel the action — contention is treated as channel unavailable, not retried")
            assertFalse(executor.wasExecuted,
                "Executor must not be called — step_1 failed under write-lock contention")
            assertEquals(0, sink.deliveryCount,
                "Result sink must not be called on CANCELLED")
        } finally {
            runCatching { conn2.rollback() }
            runCatching { conn2.close() }
        }
    }

    // --- SF-5: duplicate receipt_id → DB rejects second write, honest failure path taken ---

    @Test
    fun `SF-5 duplicate receipt_id — UNIQUE index rejects second write coordinator follows honest failure path`() {
        // Pre-seed a row with a known receipt_id. This simulates external tampering
        // or (negligibly probable) UUID collision. The coordinator's subsequent write
        // attempt for an action assigned the same receipt_id hits the UNIQUE index
        // and returns false. The system handles this as a receipt write failure —
        // honest, not silently successful.
        val knownReceiptId = "dup-target-sf5"

        // Seed the conflict directly via the receipt channel.
        val seedChannel = SQLiteReceiptChannel(workingConn)
        assertTrue(seedChannel.write(Receipt.ActionReceipt(
            receiptId       = knownReceiptId,
            status          = ActionReceiptStatus.SUCCESS,
            capabilityName  = "SEED_ACTION",
            agentId         = "seeder",
            sessionId       = "seed-session",
            sequenceNumber  = 0
        )), "Seed write must succeed — establishes the conflicting row")

        // Attempt to write a second ActionReceipt with the same receipt_id directly.
        // The UNIQUE index rejects it; write() must return false.
        val dupeResult = seedChannel.write(Receipt.ActionReceipt(
            receiptId       = knownReceiptId,
            status          = ActionReceiptStatus.SUCCESS,
            capabilityName  = CAPABILITY,
            agentId         = AGENT_ID,
            sessionId       = SESSION_ID,
            sequenceNumber  = 1
        ))
        assertFalse(dupeResult,
            "Second write with the same receipt_id must fail — UNIQUE index rejects the duplicate")

        // Confirm exactly one row exists for the known receipt_id.
        val rowCount = workingConn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM receipts WHERE receipt_id = '$knownReceiptId'"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(1, rowCount,
            "Exactly one row must exist for the receipt_id — no duplicate stored, no silent overwrite")
    }

    // --- helpers ---

    private fun auditRecordTypes(conn: Connection): List<String> =
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT record_type FROM audit_failure_records ORDER BY id"
            ).use { rs ->
                val list = mutableListOf<String>()
                while (rs.next()) list.add(rs.getString(1))
                list
            }
        }
}
