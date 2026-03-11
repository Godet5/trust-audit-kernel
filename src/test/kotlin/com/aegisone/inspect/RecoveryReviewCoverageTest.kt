package com.aegisone.inspect

import com.aegisone.db.ReviewDbBootstrap
import com.aegisone.db.SQLiteArtifactLockManager
import com.aegisone.db.SQLiteArtifactStore
import com.aegisone.db.SQLiteAuditFailureChannel
import com.aegisone.db.SQLiteBootstrap
import com.aegisone.db.SQLiteReceiptChannel
import com.aegisone.db.SQLiteSessionRegistry
import com.aegisone.db.SharedConnection
import com.aegisone.boot.StartupRecovery
import com.aegisone.execution.AuditRecord
import com.aegisone.review.ArtifactState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P4 coverage: proves requiresHumanReview under real degraded and cross-table
 * conditions, using the full production stack (SQLite-backed channels, inspector,
 * and StartupRecovery).
 *
 * These tests prove the *visibility* of truth, not the mechanics (P1–P6).
 * The operator surface must never lie about system state.
 *
 * RR-1: Cross-table violation triggers human review — inspector reports
 *        requiresHumanReview before recovery, violations visible in summary.
 *
 * RR-2: Degraded inspection forces review requirement — when the review DB
 *        is absent, "couldn't check" must mean "must review", never "safe."
 *
 * RR-3: Recovery summary reflects unresolvable failures — readyForActive
 *        is false AND inspector requiresHumanReview is true when
 *        UnauditedIrreversible survives recovery.
 *
 * RR-4: Inspector state transition correctness — degraded → healthy transition
 *        clears requiresHumanReview only when no violations remain.
 */
class RecoveryReviewCoverageTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var receiptShared: SharedConnection
    private lateinit var reviewShared: SharedConnection
    private lateinit var sessionRegistry: SQLiteSessionRegistry
    private lateinit var artifactStore: SQLiteArtifactStore
    private lateinit var lockMgr: SQLiteArtifactLockManager
    private lateinit var receiptChannel: SQLiteReceiptChannel

    @BeforeEach
    fun setup() {
        receiptShared   = SQLiteBootstrap.openAndInitialize(File(tempDir, "receipts.db").absolutePath)
        reviewShared    = ReviewDbBootstrap.openAndInitialize(File(tempDir, "review.db").absolutePath)
        sessionRegistry = SQLiteSessionRegistry(reviewShared)
        artifactStore   = SQLiteArtifactStore(reviewShared)
        lockMgr         = SQLiteArtifactLockManager(reviewShared)
        receiptChannel  = SQLiteReceiptChannel(receiptShared)
    }

    @AfterEach
    fun teardown() {
        runCatching { receiptShared.close() }
        runCatching { reviewShared.close() }
    }

    // --- helpers ---

    private fun insertSession(sessionId: String, state: String) {
        val now = System.currentTimeMillis()
        reviewShared.conn.prepareStatement(
            "INSERT INTO sessions (session_id, cert_fingerprint, state, created_at_ms, last_heartbeat_ms, expiry_ms) " +
            "VALUES (?, 'fp-rr', ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, state)
            ps.setLong(3, now)
            ps.setLong(4, now)
            ps.setLong(5, Long.MAX_VALUE)
            ps.executeUpdate()
        }
    }

    private fun insertLockDirect(artifactId: String, sessionId: String) {
        reviewShared.conn.prepareStatement(
            "INSERT INTO artifact_locks (artifact_id, session_id, acquired_at_ms) VALUES (?, ?, ?)"
        ).use { ps ->
            ps.setString(1, artifactId)
            ps.setString(2, sessionId)
            ps.setLong(3, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    // --- RR-1: Cross-table violation triggers human review ---

    @Test
    fun `RR-1 cross-table violations visible in inspector before recovery — requiresHumanReview true`() {
        // Inject all three violation types
        // Violation A: UNDER_REVIEW artifact with no lock
        artifactStore.setState("art-rr1-nolock", ArtifactState.UNDER_REVIEW)

        // Violation B: ghost lock (lock entry, no sessions row)
        insertLockDirect("art-rr1-ghost", "ghost-sess-rr1")

        // Violation C: EXPIRED session with outstanding lock
        insertSession("sess-rr1-expired", "EXPIRED")
        insertLockDirect("art-rr1-expired", "sess-rr1-expired")

        // Inspector BEFORE recovery: violations must be visible
        val inspectorBefore = SystemInspector(receiptShared, reviewShared)
        val summaryBefore = inspectorBefore.recoverySummary()

        assertTrue(summaryBefore.violationA >= 1,
            "violationA must count the stranded UNDER_REVIEW artifact")
        assertTrue(summaryBefore.violationB >= 1,
            "violationB must count the ghost lock")
        assertTrue(summaryBefore.violationC >= 1,
            "violationC must count the EXPIRED session with a lock")
        assertFalse(summaryBefore.isClean,
            "isClean must be false when violations exist")
        assertTrue(summaryBefore.requiresHumanReview,
            "requiresHumanReview must be true when cross-table violations exist")
        assertFalse(summaryBefore.isDegraded,
            "isDegraded must be false — review DB is connected")

        // Run recovery — violations are repaired
        val result = StartupRecovery(
            receiptShared   = receiptShared,
            sessionRegistry = sessionRegistry,
            artifactStore   = artifactStore,
            lockManager     = lockMgr,
            receiptChannel  = receiptChannel,
            reviewConn      = reviewShared.conn
        ).run()
        assertTrue(result.readyForActive, "readyForActive must be true — repairs completed")

        // Inspector AFTER recovery: violations must be cleared
        val inspectorAfter = SystemInspector(receiptShared, reviewShared)
        val summaryAfter = inspectorAfter.recoverySummary()
        assertEquals(0, summaryAfter.violationA, "violationA must be 0 after repair")
        assertEquals(0, summaryAfter.violationB, "violationB must be 0 after repair")
        assertEquals(0, summaryAfter.violationC, "violationC must be 0 after repair")
        assertFalse(summaryAfter.requiresHumanReview,
            "requiresHumanReview must be false after all violations are repaired")

        // Repair receipts must be in the receipt DB (proof that violations were real)
        val repairReceipts = receiptShared.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM receipts WHERE receipt_type = 'ProposalStatusReceipt'"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        assertTrue(repairReceipts >= 1,
            "ProposalStatusReceipts must exist — proof that violations were real and repaired")
    }

    // --- RR-2: Degraded inspection forces review requirement ---

    @Test
    fun `RR-2 degraded inspection — requiresHumanReview true when review DB absent`() {
        // Inspector with NO review DB
        val inspector = SystemInspector(receiptShared)  // reviewShared omitted
        val summary = inspector.recoverySummary()

        assertTrue(summary.isDegraded,
            "isDegraded must be true — review DB not connected")
        assertFalse(summary.isClean,
            "isClean must be false — 'couldn't check' is not 'all clear'")
        assertTrue(summary.requiresHumanReview,
            "requiresHumanReview must be true when degraded — unknown state requires human review")

        // Confirm the specific degradation markers
        assertEquals(-1, summary.activeSessions, "activeSessions must be -1 (unknown)")
        assertEquals(-1, summary.violationA, "violationA must be -1 (unknown)")
        assertEquals(-1, summary.violationB, "violationB must be -1 (unknown)")
        assertEquals(-1, summary.violationC, "violationC must be -1 (unknown)")
    }

    // --- RR-3: Recovery summary reflects unresolvable failures ---

    @Test
    fun `RR-3 UnauditedIrreversible survives recovery — readyForActive false and requiresHumanReview true`() {
        // Inject an UnauditedIrreversible record (unrepairable — only human can resolve)
        val auditChannel = SQLiteAuditFailureChannel(receiptShared)
        assertTrue(auditChannel.write(AuditRecord.UnauditedIrreversible(
            receiptId = "irrev-rr3",
            detail    = "RECEIPT_WRITE_FAILED for DELETE_ALL — irreversible action completed without audit trail"
        )))

        // Also inject a cross-table violation for compounding effect
        artifactStore.setState("art-rr3-stuck", ArtifactState.UNDER_REVIEW)

        // Run recovery
        val result = StartupRecovery(
            receiptShared   = receiptShared,
            sessionRegistry = sessionRegistry,
            artifactStore   = artifactStore,
            lockManager     = lockMgr,
            receiptChannel  = receiptChannel,
            reviewConn      = reviewShared.conn
        ).run()

        // Recovery result: UNRESOLVED_FAILURES blocks ACTIVE
        assertFalse(result.readyForActive,
            "readyForActive must be false — UnauditedIrreversible is unresolvable by automation")

        // Inspector confirms the operator surface matches
        val inspector = SystemInspector(receiptShared, reviewShared)
        val summary = inspector.recoverySummary()

        assertEquals(1, summary.unresolvedIrreversible,
            "unresolvedIrreversible must survive recovery — human resolution required")
        assertTrue(summary.requiresHumanReview,
            "requiresHumanReview must be true — unresolved irreversible requires human attention")
        assertFalse(summary.isClean,
            "isClean must be false — system has unresolved state")

        // Cross-table violation was repaired (Phase 3), but irreversible persists (Phase 1)
        assertEquals(0, summary.violationA,
            "violationA must be 0 — repair completed for cross-table")
    }

    // --- RR-4: Inspector state transition correctness ---

    @Test
    fun `RR-4 inspector transitions from degraded to healthy — requiresHumanReview clears only when violations cleared`() {
        // Step 1: Degraded mode — no review DB
        val degradedInspector = SystemInspector(receiptShared)
        val degradedSummary = degradedInspector.recoverySummary()

        assertTrue(degradedSummary.isDegraded,
            "Step 1: must be degraded without review DB")
        assertTrue(degradedSummary.requiresHumanReview,
            "Step 1: requiresHumanReview must be true in degraded mode")

        // Step 2: Connect review DB but inject a violation
        artifactStore.setState("art-rr4-stuck", ArtifactState.UNDER_REVIEW)

        val violatingInspector = SystemInspector(receiptShared, reviewShared)
        val violatingSummary = violatingInspector.recoverySummary()

        assertFalse(violatingSummary.isDegraded,
            "Step 2: must NOT be degraded — review DB is now connected")
        assertTrue(violatingSummary.requiresHumanReview,
            "Step 2: requiresHumanReview must be true — violation A exists")
        assertTrue(violatingSummary.violationA >= 1,
            "Step 2: violationA must be >= 1")

        // Step 3: Run recovery to clear the violation
        StartupRecovery(
            receiptShared   = receiptShared,
            sessionRegistry = sessionRegistry,
            artifactStore   = artifactStore,
            lockManager     = lockMgr,
            receiptChannel  = receiptChannel,
            reviewConn      = reviewShared.conn
        ).run()

        // Step 4: Clean connected state — requiresHumanReview must now be false
        val cleanInspector = SystemInspector(receiptShared, reviewShared)
        val cleanSummary = cleanInspector.recoverySummary()

        assertFalse(cleanSummary.isDegraded,
            "Step 4: must NOT be degraded — review DB connected")
        assertFalse(cleanSummary.requiresHumanReview,
            "Step 4: requiresHumanReview must be false — no violations, not degraded")
        assertTrue(cleanSummary.isClean,
            "Step 4: isClean must be true — all fields known and zero")
    }
}
