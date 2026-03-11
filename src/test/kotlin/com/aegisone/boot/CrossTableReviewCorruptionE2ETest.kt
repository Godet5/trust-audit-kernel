package com.aegisone.boot

import com.aegisone.db.ReviewDbBootstrap
import com.aegisone.db.SQLiteArtifactLockManager
import com.aegisone.db.SQLiteArtifactStore
import com.aegisone.db.SQLiteBootstrap
import com.aegisone.db.SQLiteReceiptChannel
import com.aegisone.db.SQLiteSessionRegistry
import com.aegisone.db.SharedConnection
import com.aegisone.review.ArtifactState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial cross-table review corruption tests.
 *
 * Proves the review subsystem is repaired at startup under three violation classes
 * that the nominal expiry sweep cannot reach, and that no unintended authority is
 * granted in the process.
 *
 * RC-1: Violation A — UNDER_REVIEW artifact with no lock entry.
 *        The artifact is stuck in UNDER_REVIEW state with no lock holder.
 *        StartupRecovery reverts it to SUBMITTED and writes a receipt.
 *
 * RC-2: Violation B — Lock entry with a session_id absent from the sessions table.
 *        A ghost lock: the session was deleted but the lock persisted.
 *        StartupRecovery releases the lock, reverts artifact, writes receipt.
 *
 * RC-3: Violation C — EXPIRED session with an outstanding lock (pre-existing EXPIRED row,
 *        not produced by the current sweep). The expiry sweep only processes sessions
 *        transitioning from ACTIVE to EXPIRED in the current run; a session already in
 *        EXPIRED state with locks remaining from a crash is invisible to it.
 *        StartupRecovery releases locks, reverts artifacts, writes receipts.
 *
 * RC-4: All three violations simultaneously — repaired independently.
 *        Proves violations don't interfere with each other and repair is additive.
 *
 * RC-5: Clean state — no spurious repairs. An artifact in SUBMITTED state with no lock
 *        must not be touched. An ACTIVE session with no locks must not produce receipts.
 *
 * "Without inventing authority": no new capabilities are granted and no UNDER_REVIEW
 * state is left standing. The only authority change is reversion to SUBMITTED — a
 * reduction of locked review access, never an expansion.
 *
 * Production code paired with this file:
 *   ReviewCrossTableReconciliation.kt (new) — the three-phase repair component.
 *   StartupRecovery.kt (modified) — Phase 3: runs ReviewCrossTableReconciliation
 *     when reviewConn is provided. Zero behavior change for callers that omit reviewConn.
 */
class CrossTableReviewCorruptionE2ETest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var receiptShared: SharedConnection
    private lateinit var reviewShared: SharedConnection
    private lateinit var artifactStore: SQLiteArtifactStore
    private lateinit var lockMgr: SQLiteArtifactLockManager
    private lateinit var sessionRegistry: SQLiteSessionRegistry
    private lateinit var receiptChannel: SQLiteReceiptChannel

    @BeforeEach
    fun setup() {
        receiptShared   = SQLiteBootstrap.openAndInitialize(File(tempDir, "receipts.db").absolutePath)
        reviewShared    = ReviewDbBootstrap.openAndInitialize(File(tempDir, "review.db").absolutePath)
        artifactStore   = SQLiteArtifactStore(reviewShared)
        lockMgr         = SQLiteArtifactLockManager(reviewShared)
        sessionRegistry = SQLiteSessionRegistry(reviewShared)
        receiptChannel  = SQLiteReceiptChannel(receiptShared)
    }

    @AfterEach
    fun teardown() {
        runCatching { receiptShared.close() }
        runCatching { reviewShared.close() }
    }

    private fun runRecovery() = StartupRecovery(
        receiptShared   = receiptShared,
        sessionRegistry = sessionRegistry,
        artifactStore   = artifactStore,
        lockManager     = lockMgr,
        receiptChannel  = receiptChannel,
        reviewConn      = reviewShared.conn
    ).run()

    /** Insert a row directly into the sessions table with a given state. */
    private fun insertSession(sessionId: String, state: String, expiryMs: Long = Long.MAX_VALUE) {
        val now = System.currentTimeMillis()
        reviewShared.conn.prepareStatement(
            "INSERT INTO sessions (session_id, cert_fingerprint, state, created_at_ms, last_heartbeat_ms, expiry_ms) " +
            "VALUES (?, 'fp-test', ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, state)
            ps.setLong(3, now)
            ps.setLong(4, now)
            ps.setLong(5, expiryMs)
            ps.executeUpdate()
        }
    }

    /** Insert a lock entry directly, bypassing the normal lock acquisition path. */
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

    private fun proposalStatusReceipts(artifactId: String): List<Triple<String, String, String>> =
        receiptShared.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT artifact_id, from_state, to_state, changed_by FROM receipts " +
                "WHERE receipt_type = 'ProposalStatusReceipt' AND artifact_id = '$artifactId'"
            ).use { rs ->
                val list = mutableListOf<Triple<String, String, String>>()
                while (rs.next()) list.add(
                    Triple(rs.getString("to_state"), rs.getString("changed_by"), rs.getString("artifact_id"))
                )
                list
            }
        }

    // --- RC-1: UNDER_REVIEW artifact with no lock ---

    @Test
    fun `RC-1 UNDER_REVIEW artifact with no lock — reverted to SUBMITTED receipt written`() {
        // Inject Violation A: artifact stuck in UNDER_REVIEW, no lock entry.
        artifactStore.setState("art-rc1", ArtifactState.UNDER_REVIEW)
        // Confirm: no lock exists for this artifact.
        assertNull(lockMgr.lockHolder("art-rc1"), "No lock must be present before recovery")

        runRecovery()

        // Artifact reverted to SUBMITTED.
        assertEquals(ArtifactState.SUBMITTED, artifactStore.getState("art-rc1"),
            "UNDER_REVIEW artifact with no lock must be reverted to SUBMITTED")

        // ProposalStatusReceipt written with correct attribution.
        val receipts = proposalStatusReceipts("art-rc1")
        assertTrue(receipts.any { (toState, changedBy, _) ->
            toState == "SUBMITTED" && changedBy == "SYSTEM_RECOVERY_NO_LOCK"
        }, "Receipt must record the reversion with SYSTEM_RECOVERY_NO_LOCK attribution")

        // No lock was granted — we only reduced authority, not extended it.
        assertNull(lockMgr.lockHolder("art-rc1"), "No lock must exist after recovery")
    }

    // --- RC-2: lock with ghost session (no sessions row) ---

    @Test
    fun `RC-2 ghost lock no sessions row — lock released artifact reverted receipt written`() {
        // Inject Violation B: artifact locked by a session_id that has no sessions row.
        artifactStore.setState("art-rc2", ArtifactState.UNDER_REVIEW)
        insertLockDirect("art-rc2", "ghost-sess-rc2")
        // Confirm no sessions row exists.
        assertNull(sessionRegistry.lookup("ghost-sess-rc2"), "Ghost session must not be in registry")

        runRecovery()

        // Lock released.
        assertNull(lockMgr.lockHolder("art-rc2"), "Ghost lock must be released by recovery")

        // Artifact reverted.
        assertEquals(ArtifactState.SUBMITTED, artifactStore.getState("art-rc2"),
            "Artifact with ghost lock must be reverted to SUBMITTED")

        // Receipt written with ghost-lock attribution.
        val receipts = proposalStatusReceipts("art-rc2")
        assertTrue(receipts.any { (toState, changedBy, _) ->
            toState == "SUBMITTED" && changedBy == "SYSTEM_RECOVERY_GHOST_LOCK"
        }, "Receipt must record the reversion with SYSTEM_RECOVERY_GHOST_LOCK attribution")
    }

    // --- RC-3: EXPIRED session with outstanding lock (pre-existing EXPIRED row) ---

    @Test
    fun `RC-3 pre-existing EXPIRED session with lock — lock released artifact reverted receipt written`() {
        // Inject Violation C: session is already EXPIRED in the DB (from a previous crash
        // that set the state but didn't complete the cleanup sweep), lock still outstanding.
        artifactStore.setState("art-rc3", ArtifactState.UNDER_REVIEW)
        insertSession("sess-rc3-expired", state = "EXPIRED", expiryMs = 1L)
        insertLockDirect("art-rc3", "sess-rc3-expired")

        // Confirm: the expiry sweep with now=currentTime wouldn't pick this up
        // (session is already EXPIRED, not ACTIVE), so this artifact would stay stuck
        // without Phase 3.
        runRecovery()

        // Lock released.
        assertNull(lockMgr.lockHolder("art-rc3"), "Lock held by EXPIRED session must be released")

        // Artifact reverted.
        assertEquals(ArtifactState.SUBMITTED, artifactStore.getState("art-rc3"),
            "Artifact locked by EXPIRED session must be reverted to SUBMITTED")

        // Receipt written.
        val receipts = proposalStatusReceipts("art-rc3")
        assertTrue(receipts.any { (toState, changedBy, _) ->
            toState == "SUBMITTED" && changedBy == "SYSTEM_RECOVERY_EXPIRED_SESSION"
        }, "Receipt must record the reversion with SYSTEM_RECOVERY_EXPIRED_SESSION attribution")
    }

    // --- RC-4: all three violations simultaneously ---

    @Test
    fun `RC-4 all three violations simultaneously — each repaired independently no interference`() {
        // Violation A: stranded UNDER_REVIEW artifact
        artifactStore.setState("art-rc4-nolock", ArtifactState.UNDER_REVIEW)

        // Violation B: ghost lock
        artifactStore.setState("art-rc4-ghost", ArtifactState.UNDER_REVIEW)
        insertLockDirect("art-rc4-ghost", "ghost-sess-rc4")

        // Violation C: pre-existing EXPIRED session with lock
        artifactStore.setState("art-rc4-expired", ArtifactState.UNDER_REVIEW)
        insertSession("sess-rc4-expired", state = "EXPIRED", expiryMs = 1L)
        insertLockDirect("art-rc4-expired", "sess-rc4-expired")

        runRecovery()

        // All three artifacts reverted.
        assertEquals(ArtifactState.SUBMITTED, artifactStore.getState("art-rc4-nolock"),
            "Stranded UNDER_REVIEW artifact must be reverted")
        assertEquals(ArtifactState.SUBMITTED, artifactStore.getState("art-rc4-ghost"),
            "Artifact with ghost lock must be reverted")
        assertEquals(ArtifactState.SUBMITTED, artifactStore.getState("art-rc4-expired"),
            "Artifact with expired-session lock must be reverted")

        // All three locks cleared.
        assertNull(lockMgr.lockHolder("art-rc4-ghost"),   "Ghost lock must be released")
        assertNull(lockMgr.lockHolder("art-rc4-expired"), "Expired-session lock must be released")

        // All three receipts written with distinct attribution.
        assertTrue(proposalStatusReceipts("art-rc4-nolock").any { it.second == "SYSTEM_RECOVERY_NO_LOCK" },
            "Stranded artifact receipt must carry SYSTEM_RECOVERY_NO_LOCK")
        assertTrue(proposalStatusReceipts("art-rc4-ghost").any { it.second == "SYSTEM_RECOVERY_GHOST_LOCK" },
            "Ghost-lock artifact receipt must carry SYSTEM_RECOVERY_GHOST_LOCK")
        assertTrue(proposalStatusReceipts("art-rc4-expired").any { it.second == "SYSTEM_RECOVERY_EXPIRED_SESSION" },
            "Expired-session artifact receipt must carry SYSTEM_RECOVERY_EXPIRED_SESSION")
    }

    // --- RC-5: clean state — no spurious repairs ---

    @Test
    fun `RC-5 clean state — no spurious repairs no receipts written for consistent artifacts`() {
        // Consistent state: SUBMITTED artifact with no lock, active session with no locks.
        artifactStore.setState("art-rc5-clean", ArtifactState.SUBMITTED)
        insertSession("sess-rc5-active", state = "ACTIVE", expiryMs = Long.MAX_VALUE)

        runRecovery()

        // State unchanged.
        assertEquals(ArtifactState.SUBMITTED, artifactStore.getState("art-rc5-clean"),
            "Clean SUBMITTED artifact must not be modified by recovery")

        // No spurious receipts.
        val receipts = proposalStatusReceipts("art-rc5-clean")
        assertTrue(receipts.isEmpty(),
            "No ProposalStatusReceipt must be written for an artifact that was already consistent")
    }
}
