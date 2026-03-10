package com.aegisone.boot

import com.aegisone.db.ReviewDbBootstrap
import com.aegisone.db.SQLiteArtifactLockManager
import com.aegisone.db.SQLiteArtifactStore
import com.aegisone.db.SQLiteAuditFailureChannel
import com.aegisone.db.SQLiteBootstrap
import com.aegisone.db.SQLiteReceiptChannel
import com.aegisone.db.SQLiteSessionRegistry
import com.aegisone.db.StartupReconciliation.ReconciliationResult
import com.aegisone.execution.AuditRecord
import com.aegisone.receipt.ActionReceiptStatus
import com.aegisone.receipt.Receipt
import com.aegisone.review.ArtifactState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end dirty-restart recovery tests.
 *
 * Proves the live system can start from damaged-but-valid persisted state,
 * repair what is repairable, surface what is indeterminate, and return to
 * ACTIVE without violating the existing invariants.
 *
 * Dirty state is injected via the real channel classes — the same write
 * paths the system would use in production. Only the review state
 * (stale session + locked artifact) is set up via the real registry/store
 * management methods.
 *
 * DR-1: Multi-scenario dirty restart — three concurrent forms of dirty state:
 *         (a) orphaned PENDING in audit DB (W1 crash window)
 *         (b) ActionReceipt without summary (W3 crash window)
 *         (c) stale ACTIVE session + locked artifact in UNDER_REVIEW
 *       StartupRecovery repairs all three, returns REPAIRED + readyForActive.
 *
 * DR-2: UNRESOLVED_FAILURES blocks ACTIVE — UnauditedIrreversible record
 *       present; StartupRecovery returns UNRESOLVED_FAILURES + !readyForActive.
 */
class DirtyRestartE2ETest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var receiptConn: Connection
    private lateinit var reviewConn: Connection

    private lateinit var sessionRegistry: SQLiteSessionRegistry
    private lateinit var artifactStore: SQLiteArtifactStore
    private lateinit var lockMgr: SQLiteArtifactLockManager
    private lateinit var receiptChannel: SQLiteReceiptChannel
    private lateinit var auditChannel: SQLiteAuditFailureChannel

    private val CERT       = "cert-dr-e2e"
    private val FAR_PAST   = System.currentTimeMillis() - 3_600_000L

    @BeforeEach
    fun setup() {
        receiptConn = SQLiteBootstrap.openAndInitialize(File(tempDir, "receipts.db").absolutePath)
        reviewConn  = ReviewDbBootstrap.openAndInitialize(File(tempDir, "review.db").absolutePath)
        sessionRegistry = SQLiteSessionRegistry(reviewConn)
        artifactStore   = SQLiteArtifactStore(reviewConn)
        lockMgr         = SQLiteArtifactLockManager(reviewConn)
        receiptChannel  = SQLiteReceiptChannel(receiptConn)
        auditChannel    = SQLiteAuditFailureChannel(receiptConn)
    }

    @AfterEach
    fun teardown() {
        runCatching { receiptConn.close() }
        runCatching { reviewConn.close() }
    }

    // --- DR-1: three concurrent dirty-state scenarios repaired in one sweep ---

    @Test
    fun `DR-1 multi-scenario dirty restart — all three scenarios repaired, readyForActive true`() {
        // --- Inject dirty state ---

        // (a) Orphaned PENDING: write a Pending audit record with no corresponding ActionReceipt.
        //     Simulates a crash between step_1 (PENDING written) and step_3 (receipt written).
        val orphanReceiptId = "orphan-pending-dr1"
        assertTrue(auditChannel.write(AuditRecord.Pending(
            receiptId      = orphanReceiptId,
            capabilityName = "WRITE_NOTE",
            agentId        = "exec-dr1",
            sessionId      = "sess-dr1-clean",
            sequenceNumber = 1
        )), "Orphaned PENDING write must succeed")

        // (b) ActionReceipt without summary: write a full receipt but no summary.
        //     Simulates a crash in the W3 window (after step_3, before summary write).
        val noSummaryReceiptId = "no-summary-dr1"
        assertTrue(receiptChannel.write(Receipt.ActionReceipt(
            receiptId      = noSummaryReceiptId,
            status         = ActionReceiptStatus.SUCCESS,
            capabilityName = "WRITE_NOTE",
            agentId        = "exec-dr1",
            sessionId      = "sess-dr1-clean",
            sequenceNumber = 2
        )), "ActionReceipt write must succeed")

        // (c) Stale session + locked artifact: session opened with past expiry,
        //     artifact in UNDER_REVIEW, locked by the stale session.
        //     Simulates a crash that left a session alive past its expiry.
        val staleSessionId = "sess-stale-dr1"
        val staleArtifactId = "art-stale-dr1"
        assertTrue(sessionRegistry.openSession(staleSessionId, CERT, FAR_PAST),
            "Stale session setup must succeed")
        artifactStore.setState(staleArtifactId, ArtifactState.UNDER_REVIEW)
        lockMgr.lock(staleArtifactId, staleSessionId)

        // Confirm dirty state before recovery
        assertEquals(staleSessionId, lockMgr.lockHolder(staleArtifactId))
        assertEquals(ArtifactState.UNDER_REVIEW, artifactStore.getState(staleArtifactId))

        // --- Run startup recovery ---
        val result = StartupRecovery(
            receiptConn     = receiptConn,
            sessionRegistry = sessionRegistry,
            artifactStore   = artifactStore,
            lockManager     = lockMgr,
            receiptChannel  = receiptChannel
        ).run()

        // --- Verify recovery outcome ---

        // Reconciliation found and repaired the orphaned PENDING and summary gap
        assertEquals(ReconciliationResult.REPAIRED, result.reconciliation,
            "Reconciliation must return REPAIRED for orphaned PENDING + summary gap")

        // One stale session was swept and cleaned up
        assertEquals(1, result.expiredSessions,
            "ExpiryCoordinator must report one expired session")

        // Coordinator may proceed to ACTIVE
        assertTrue(result.readyForActive,
            "System must be readyForActive after successful repair")

        // (a) Orphaned PENDING was marked FAILED — a Failed record with reason W1_ORPHANED_PENDING
        //     should now exist for the orphan receipt_id
        val failedCount = receiptConn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM audit_failure_records " +
                "WHERE record_type = 'Failed' AND receipt_id = '$orphanReceiptId'"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(1, failedCount,
            "Orphaned PENDING must be marked Failed by reconciliation")

        // (b) Missing summary was regenerated
        val summaryCount = receiptConn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM receipt_summaries " +
                "WHERE receipt_id = '$noSummaryReceiptId' AND regenerated = 1"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(1, summaryCount,
            "Summary gap must be regenerated with regenerated=1 flag")

        // (c) Lock released
        assertNull(lockMgr.lockHolder(staleArtifactId),
            "Lock must be released after expiry sweep")

        // (c) Artifact reverted to SUBMITTED
        assertEquals(ArtifactState.SUBMITTED, artifactStore.getState(staleArtifactId),
            "Artifact must revert to SUBMITTED after expiry sweep")

        // (c) ProposalStatusReceipt durably written for the reverted artifact
        val proposalReceiptCount = receiptConn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM receipts " +
                "WHERE receipt_type = 'ProposalStatusReceipt' " +
                "AND artifact_id = '$staleArtifactId' " +
                "AND to_state = 'SUBMITTED'"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(1, proposalReceiptCount,
            "ProposalStatusReceipt must be written for the reverted artifact")
    }

    // --- DR-2: UNRESOLVED_FAILURES blocks ACTIVE ---

    @Test
    fun `DR-2 UnauditedIrreversible present — UNRESOLVED_FAILURES returned and readyForActive false`() {
        // Inject an UNAUDITED_IRREVERSIBLE record.
        // Simulates an irreversible action that completed without a durable receipt.
        // This state requires human review before the coordinator may go ACTIVE.
        assertTrue(auditChannel.write(AuditRecord.UnauditedIrreversible(
            receiptId = "unaudited-dr2",
            detail    = "Irreversible action completed; receipt write failed; cannot undo"
        )), "UnauditedIrreversible write must succeed")

        val result = StartupRecovery(
            receiptConn     = receiptConn,
            sessionRegistry = sessionRegistry,
            artifactStore   = artifactStore,
            lockManager     = lockMgr,
            receiptChannel  = receiptChannel
        ).run()

        assertEquals(ReconciliationResult.UNRESOLVED_FAILURES, result.reconciliation,
            "Reconciliation must return UNRESOLVED_FAILURES when UnauditedIrreversible exists")

        assertFalse(result.readyForActive,
            "System must NOT be readyForActive when UNRESOLVED_FAILURES are present")

        // isClean should also be false
        assertFalse(result.isClean,
            "isClean must be false when UNRESOLVED_FAILURES are present")
    }
}
