package com.aegisone.boot

import com.aegisone.db.ReviewDbBootstrap
import com.aegisone.db.SharedConnection
import com.aegisone.db.SQLiteArtifactLockManager
import com.aegisone.db.SQLiteArtifactStore
import com.aegisone.db.SQLiteAuditFailureChannel
import com.aegisone.db.SQLiteBootstrap
import com.aegisone.db.SQLiteReceiptChannel
import com.aegisone.db.SQLiteSessionRegistry
import com.aegisone.db.StartupReconciliation
import com.aegisone.db.StartupReconciliation.ReconciliationResult
import com.aegisone.execution.ActionRequest
import com.aegisone.execution.AuditRecord
import com.aegisone.execution.ConflictAlert
import com.aegisone.execution.ConflictChannel
import com.aegisone.execution.CoordinatorResult
import com.aegisone.execution.ExecutionCoordinator
import com.aegisone.execution.TrackingExecutor
import com.aegisone.review.ArtifactState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kill-window realism tests.
 *
 * Proves the system remains truthful under real interrupted-write crash windows,
 * not just injected bad state.
 *
 * The technique:
 *   1. Run real production code to produce durable state (commits that hit disk).
 *   2. Close the connection — simulating a process kill at that exact point.
 *   3. Reopen a fresh connection to the same file — simulating a restart.
 *   4. Run reconciliation — verify the repair is correct.
 *
 * This is meaningfully different from seeded tests (DR-1, RC-1/2/3):
 *   - The bad state is produced by running production code, not by direct SQL injection.
 *   - The connection close proves the WAL + synchronous=FULL durability guarantee:
 *     writes that committed before the kill must be visible after restart.
 *   - Reconciliation runs on a genuinely fresh connection — not one that saw the original writes.
 *
 * KW-1: W1 crash window — PENDING committed via auditFailureChannel, kill before execution.
 *        Restart → StartupReconciliation finds orphaned PENDING → marks FAILED.
 *        Proves: committed audit records survive the kill; the W1 repair path fires correctly.
 *
 * KW-2: W3 crash window — coordinator runs to SUCCESS (PENDING + ActionReceipt committed),
 *        kill before summary write (step_4 not yet implemented — every success is a W3 candidate).
 *        Restart → StartupReconciliation regenerates summary from ActionReceipt.
 *        Proves: committed receipt records survive; W3 gap is repaired, not lost.
 *
 * KW-3: Review mutation kill window — artifactStore.setState(UNDER_REVIEW) committed,
 *        kill before lockMgr.lock() commits.
 *        Restart → ReviewCrossTableReconciliation finds Violation A → reverts to SUBMITTED.
 *        Proves: the artifact mutation survives the kill; the recovery path correctly
 *        identifies the stranded state and reduces authority.
 *
 * KW-4: WAL atomicity — uncommitted INSERT rolled back by connection close.
 *        Restart → no partial data visible.
 *        Proves the foundational guarantee everything else depends on:
 *        data not committed before the kill does not appear after restart.
 *
 * KW-5: Full crash round-trip — W1 orphan + W3 gap + review Violation A all committed,
 *        all connections closed, full StartupRecovery run on fresh connections.
 *        Proves: a realistic multi-layer partial-state crash is fully repaired by the
 *        production recovery path, and readyForActive reflects that repair happened.
 */
class KillWindowRealismE2ETest {

    @TempDir
    lateinit var tempDir: File

    private val noOpConflict = object : ConflictChannel { override fun alert(alert: ConflictAlert) = true }

    // --- KW-1: W1 window — PENDING committed, kill before execution, reconcile on restart ---

    @Test
    fun `KW-1 W1 window — PENDING survives kill reconciler marks it FAILED on restart`() {
        val dbFile = File(tempDir, "kw1.db")

        // --- "Pre-kill" process: write PENDING and commit it ---
        val shared1    = SQLiteBootstrap.openAndInitialize(dbFile.absolutePath)
        val auditChan1 = SQLiteAuditFailureChannel(shared1)

        val receiptId = "kw1-receipt-id"
        assertTrue(auditChan1.write(AuditRecord.Pending(
            receiptId      = receiptId,
            capabilityName = "WRITE_NOTE",
            agentId        = "exec-kw1",
            sessionId      = "sess-kw1",
            sequenceNumber = 1
        )), "PENDING write must succeed — this is the last committed act before the kill")

        shared1.close()   // ← process kill: PENDING is on disk; nothing else happened

        // --- Restart: open fresh connection to the same file ---
        val shared2 = SQLiteBootstrap.openAndInitialize(dbFile.absolutePath)

        // PENDING must be visible — it committed before the kill
        val pendingRows = shared2.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT record_type FROM audit_failure_records WHERE receipt_id = '$receiptId'"
            ).use { rs -> val list = mutableListOf<String>(); while (rs.next()) list.add(rs.getString(1)); list }
        }
        assertTrue(pendingRows.contains("Pending"),
            "PENDING record must survive the kill — WAL + synchronous=FULL guarantees committed data is durable")

        // Reconcile — the PENDING has no matching ActionReceipt; it is an orphan
        val result = StartupReconciliation.run(shared2)
        assertEquals(ReconciliationResult.REPAIRED, result,
            "Orphaned PENDING must trigger REPAIRED reconciliation result")

        // Failed record must be added by reconciliation, with W1 attribution
        val failedRows = shared2.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT reason FROM audit_failure_records WHERE record_type = 'Failed' AND receipt_id = '$receiptId'"
            ).use { rs -> val list = mutableListOf<String?>(); while (rs.next()) list.add(rs.getString(1)); list }
        }
        assertTrue(failedRows.any { it == "W1_ORPHANED_PENDING" },
            "Reconciliation must add a Failed record with W1_ORPHANED_PENDING reason for the orphan")

        runCatching { shared2.close() }
    }

    // --- KW-2: W3 window — coordinator runs to SUCCESS, kill before summary, regenerate ---

    @Test
    fun `KW-2 W3 window — ActionReceipt survives kill summary regenerated on restart`() {
        val dbFile = File(tempDir, "kw2.db")

        // --- "Pre-kill" process: coordinator runs to SUCCESS ---
        val shared1     = SQLiteBootstrap.openAndInitialize(dbFile.absolutePath)
        val auditChan   = SQLiteAuditFailureChannel(shared1)
        val receiptChan = SQLiteReceiptChannel(shared1)
        val executor    = TrackingExecutor(succeeds = true)

        val coordResult = ExecutionCoordinator(
            auditFailureChannel = auditChan,
            receiptChannel      = receiptChan,
            executor            = executor,
            conflictChannel     = noOpConflict
        ).execute(ActionRequest("WRITE_NOTE", "exec-kw2", "sess-kw2"))

        assertEquals(CoordinatorResult.SUCCESS, coordResult,
            "Coordinator must succeed — producing committed PENDING + ActionReceipt on disk")
        assertTrue(executor.wasExecuted, "Executor must have run")

        // Verify both records are on disk before the kill
        val actionReceiptCount = shared1.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM receipts WHERE receipt_type = 'ActionReceipt'"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(1, actionReceiptCount, "ActionReceipt must be on disk before kill")

        shared1.close()   // ← process kill: PENDING + ActionReceipt on disk; summary never written

        // --- Restart ---
        val shared2 = SQLiteBootstrap.openAndInitialize(dbFile.absolutePath)

        // ActionReceipt must survive
        val survivedReceipt = shared2.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM receipts WHERE receipt_type = 'ActionReceipt'"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(1, survivedReceipt,
            "ActionReceipt must survive the kill — committed before the process died")

        // No summary exists yet (step_4 not yet implemented; every SUCCESS is a W3 candidate)
        val summaryBefore = shared2.conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM receipt_summaries").use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(0, summaryBefore, "No summary must exist before reconciliation runs")

        // Reconcile — W3 gap detected, summary regenerated
        val result = StartupReconciliation.run(shared2)
        assertEquals(ReconciliationResult.REPAIRED, result,
            "W3 summary gap must trigger REPAIRED result")

        // Summary must now exist, marked as regenerated
        val summaryRow = shared2.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT regenerated, capability_name FROM receipt_summaries"
            ).use { rs ->
                if (rs.next()) Pair(rs.getInt("regenerated"), rs.getString("capability_name")) else null
            }
        }
        assertTrue(summaryRow != null, "Summary must exist after W3 reconciliation")
        assertEquals(1, summaryRow!!.first, "Summary must be flagged regenerated=1")
        assertEquals("WRITE_NOTE", summaryRow.second, "Summary must carry the correct capability_name")

        runCatching { shared2.close() }
    }

    // --- KW-3: review mutation kill window — UNDER_REVIEW committed, lock write never happened ---

    @Test
    fun `KW-3 review mutation kill — UNDER_REVIEW state survives kill recovery reverts without lock`() {
        val reviewFile  = File(tempDir, "kw3-review.db")
        val receiptFile = File(tempDir, "kw3-receipts.db")

        // --- "Pre-kill" process: setState commits, lock write never happens ---
        val reviewShared1  = ReviewDbBootstrap.openAndInitialize(reviewFile.absolutePath)
        val artifactStore1 = SQLiteArtifactStore(reviewShared1)

        artifactStore1.setState("art-kw3", ArtifactState.UNDER_REVIEW)
        // Process killed here — lock write that would normally follow never commits
        reviewShared1.close()

        // --- Restart ---
        val reviewShared2  = ReviewDbBootstrap.openAndInitialize(reviewFile.absolutePath)
        val receiptShared2 = SQLiteBootstrap.openAndInitialize(receiptFile.absolutePath)
        val artifactStore2 = SQLiteArtifactStore(reviewShared2)
        val lockMgr2       = SQLiteArtifactLockManager(reviewShared2)
        val receiptChan2   = SQLiteReceiptChannel(receiptShared2)

        // UNDER_REVIEW state must survive the kill
        assertEquals(ArtifactState.UNDER_REVIEW, artifactStore2.getState("art-kw3"),
            "UNDER_REVIEW state must survive the kill — the setState() committed before the process died")

        // No lock exists — the lock write never happened
        assertNull(lockMgr2.lockHolder("art-kw3"),
            "No lock must exist — the lock write was killed before committing")

        // Run Phase 3 reconciliation — Violation A detected and repaired
        val repairResult = ReviewCrossTableReconciliation(reviewShared2.conn, artifactStore2, lockMgr2, receiptChan2).run()
        assertEquals(1, repairResult.repairedUnlockedReviews,
            "Exactly one Violation A (UNDER_REVIEW with no lock) must be repaired")

        // Artifact reverted to SUBMITTED — authority reduced, not extended
        assertEquals(ArtifactState.SUBMITTED, artifactStore2.getState("art-kw3"),
            "Artifact must be reverted to SUBMITTED — recovery reduces authority")

        // Receipt written for the reversion
        val receiptCount = receiptShared2.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM receipts WHERE receipt_type = 'ProposalStatusReceipt' " +
                "AND artifact_id = 'art-kw3' AND changed_by = 'SYSTEM_RECOVERY_NO_LOCK'"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(1, receiptCount, "ProposalStatusReceipt must be written for the kill-window reversion")

        runCatching { reviewShared2.close(); receiptShared2.close() }
    }

    // --- KW-4: WAL atomicity — uncommitted data not visible after kill ---

    @Test
    fun `KW-4 WAL atomicity — uncommitted INSERT not visible after connection close and reopen`() {
        val dbFile = File(tempDir, "kw4.db")

        // --- "Pre-kill" process: start a write transaction but do not commit ---
        val shared1 = SQLiteBootstrap.openAndInitialize(dbFile.absolutePath)
        shared1.conn.autoCommit = false
        shared1.conn.prepareStatement(
            "INSERT INTO receipts (receipt_type, timestamp_ms, detail, inserted_at_ms) " +
            "VALUES ('ActionReceipt', 0, 'uncommitted-kw4', 0)"
        ).use { it.executeUpdate() }
        // Do NOT commit — simulate process kill with an active transaction
        shared1.close()   // WAL rolls back the uncommitted transaction at close time

        // --- Restart ---
        val shared2 = SQLiteBootstrap.openAndInitialize(dbFile.absolutePath)

        val count = shared2.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM receipts WHERE detail = 'uncommitted-kw4'"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(0, count,
            "Uncommitted data must not survive a connection close — WAL atomicity guarantee: " +
            "this is the foundational property that all crash-window recovery reasoning depends on")

        runCatching { shared2.close() }
    }

    // --- KW-5: full crash round-trip — W1 + W3 + review Violation A, StartupRecovery repairs all ---

    @Test
    fun `KW-5 full crash round-trip — W1 orphan W3 gap review violation all committed then StartupRecovery repairs on restart`() {
        val receiptFile = File(tempDir, "kw5-receipts.db")
        val reviewFile  = File(tempDir, "kw5-review.db")

        // --- "Pre-kill" process: produce three distinct partial states ---

        val receiptShared1 = SQLiteBootstrap.openAndInitialize(receiptFile.absolutePath)
        val reviewShared1  = ReviewDbBootstrap.openAndInitialize(reviewFile.absolutePath)

        // W1 orphan: write PENDING, kill before execution
        val orphanId = "kw5-orphan-id"
        SQLiteAuditFailureChannel(receiptShared1).write(AuditRecord.Pending(
            receiptId = orphanId, capabilityName = "WRITE_NOTE",
            agentId = "exec-kw5", sessionId = "sess-kw5", sequenceNumber = 1
        ))
        // PENDING committed; no ActionReceipt follows

        // W3 gap: coordinator runs to SUCCESS (PENDING + ActionReceipt committed), no summary
        SQLiteAuditFailureChannel(receiptShared1).write(AuditRecord.Pending(
            receiptId = "kw5-success-id", capabilityName = "READ_NOTE",
            agentId = "exec-kw5", sessionId = "sess-kw5", sequenceNumber = 2
        ))
        SQLiteReceiptChannel(receiptShared1).write(com.aegisone.receipt.Receipt.ActionReceipt(
            receiptId      = "kw5-success-id",
            status         = com.aegisone.receipt.ActionReceiptStatus.SUCCESS,
            capabilityName = "READ_NOTE",
            agentId        = "exec-kw5",
            sessionId      = "sess-kw5",
            sequenceNumber = 2
        ))
        // ActionReceipt committed; summary write never happens (process killed)

        // Review Violation A: UNDER_REVIEW committed, lock write never happens
        SQLiteArtifactStore(reviewShared1).setState("art-kw5", ArtifactState.UNDER_REVIEW)

        // Kill all connections
        receiptShared1.close()
        reviewShared1.close()

        // --- Restart: fresh connections to the same files ---
        val receiptShared2 = SQLiteBootstrap.openAndInitialize(receiptFile.absolutePath)
        val reviewShared2  = ReviewDbBootstrap.openAndInitialize(reviewFile.absolutePath)
        val sessionReg2    = SQLiteSessionRegistry(reviewShared2)
        val artifactStore2 = SQLiteArtifactStore(reviewShared2)
        val lockMgr2       = SQLiteArtifactLockManager(reviewShared2)
        val receiptChan2   = SQLiteReceiptChannel(receiptShared2)

        // Verify all three partial states survived the kill
        val pendingCount = receiptShared2.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM audit_failure_records WHERE record_type = 'Pending'"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(2, pendingCount,
            "Both PENDING records must survive the kill — two distinct crash points")

        val receiptCount = receiptShared2.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM receipts WHERE receipt_type = 'ActionReceipt'"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(1, receiptCount,
            "ActionReceipt must survive — one success before the kill")

        assertEquals(ArtifactState.UNDER_REVIEW, artifactStore2.getState("art-kw5"),
            "UNDER_REVIEW state must survive — setState committed before the kill")

        // Run full StartupRecovery — repairs all three simultaneously
        val recoveryResult = StartupRecovery(
            receiptShared   = receiptShared2,
            sessionRegistry = sessionReg2,
            artifactStore   = artifactStore2,
            lockManager     = lockMgr2,
            receiptChannel  = receiptChan2,
            reviewConn      = reviewShared2.conn
        ).run()

        // W1 + W3 both repaired → REPAIRED (not UNRESOLVED_FAILURES)
        assertEquals(ReconciliationResult.REPAIRED, recoveryResult.reconciliation,
            "StartupReconciliation must return REPAIRED — W1 orphan and W3 gap were found and fixed")

        // System may proceed to ACTIVE
        assertTrue(recoveryResult.readyForActive,
            "readyForActive must be true — REPAIRED state means all recoverable issues were fixed")

        // W1: Failed record written for the orphan
        val failedForOrphan = receiptShared2.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM audit_failure_records " +
                "WHERE record_type = 'Failed' AND receipt_id = '$orphanId' AND reason = 'W1_ORPHANED_PENDING'"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(1, failedForOrphan,
            "W1 orphan must have a corresponding Failed record after reconciliation")

        // W3: summary regenerated for the successful action
        val regenSummary = receiptShared2.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT regenerated FROM receipt_summaries WHERE receipt_id = 'kw5-success-id'"
            ).use { rs -> if (rs.next()) rs.getInt(1) else -1 }
        }
        assertEquals(1, regenSummary,
            "W3 gap must be closed — summary regenerated with regenerated=1")

        // Review Violation A: artifact reverted
        assertEquals(ArtifactState.SUBMITTED, artifactStore2.getState("art-kw5"),
            "Review violation must be repaired — artifact reverted to SUBMITTED")

        runCatching { receiptShared2.close(); reviewShared2.close() }
    }
}
