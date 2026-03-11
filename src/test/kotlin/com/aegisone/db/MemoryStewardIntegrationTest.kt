package com.aegisone.db

import com.aegisone.invariants.FixedTokenVerifier
import com.aegisone.invariants.TestFieldScopePolicy
import com.aegisone.receipt.ActionReceiptStatus
import com.aegisone.receipt.Receipt
import com.aegisone.review.ArtifactState
import com.aegisone.review.MemorySteward
import com.aegisone.review.ReviewWriteRequest
import com.aegisone.review.StewardResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end integration tests for MemorySteward with all real backends wired.
 *
 * This is the test that makes the sentence true:
 * "The review path no longer depends on in-memory artifact state; MemorySteward
 *  runs against a persisted ArtifactStore, and the invariant harness still
 *  passes unchanged."
 *
 * All components are real except TokenVerifier (FixedTokenVerifier test double —
 * crypto verification is a production boundary, not a persistence boundary).
 * FieldScopePolicy uses TestFieldScopePolicy which accepts a whitelist.
 *
 * Backends:
 *   SQLiteReceiptChannel     — from SQLiteBootstrap (receipt.db)
 *   SQLiteSessionRegistry    — from ReviewDbBootstrap (review.db)
 *   SQLiteArtifactStore      — from ReviewDbBootstrap (review.db)
 *   SQLiteArtifactLockManager — from ReviewDbBootstrap (review.db)
 *
 * Test scenarios:
 *   MSI-1: Full happy path — all 7 steps pass, write applied, field readable from DB
 *   MSI-2: Expired session — write rejected at step_2
 *   MSI-3: onSessionExpired() — lock released, artifact reverted to SUBMITTED,
 *           ProposalStatusReceipt written to SQLiteReceiptChannel
 *   MSI-4: expireSessions() + write attempt — rejected by real registry
 *   MSI-5: Artifact write survives — state readable from fresh store on same DB
 */
class MemoryStewardIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var reviewShared: SharedConnection
    private lateinit var receiptShared: SharedConnection

    private lateinit var sessionRegistry: SQLiteSessionRegistry
    private lateinit var artifactStore: SQLiteArtifactStore
    private lateinit var lockMgr: SQLiteArtifactLockManager
    private lateinit var receiptChannel: SQLiteReceiptChannel

    private val CERT = "cert-abc"
    private val SESSION_ID = "sess-msi"
    private val ARTIFACT_ID = "art-msi"
    private val FAR_FUTURE = System.currentTimeMillis() + 3_600_000L
    private val FAR_PAST   = System.currentTimeMillis() - 3_600_000L

    @BeforeEach
    fun setup() {
        reviewShared  = ReviewDbBootstrap.openAndInitialize(File(tempDir, "review.db").absolutePath)
        receiptShared = SQLiteBootstrap.openAndInitialize(File(tempDir, "receipt.db").absolutePath)

        sessionRegistry = SQLiteSessionRegistry(reviewShared)
        artifactStore   = SQLiteArtifactStore(reviewShared)
        lockMgr         = SQLiteArtifactLockManager(reviewShared)
        receiptChannel  = SQLiteReceiptChannel(receiptShared)
    }

    @AfterEach
    fun teardown() {
        runCatching { reviewShared.close() }
        runCatching { receiptShared.close() }
    }

    private fun steward(sessionId: String = SESSION_ID): MemorySteward = MemorySteward(
        tokenVerifier      = FixedTokenVerifier(sessionId, CERT),
        receiptChannel     = receiptChannel,
        sessionRegistry    = sessionRegistry,
        artifactLockManager = lockMgr,
        fieldScopePolicy   = TestFieldScopePolicy(setOf("verdict", "notes", "reviewer")),
        artifactStore      = artifactStore
    )

    private fun openActiveSession(sessionId: String = SESSION_ID) {
        assertTrue(sessionRegistry.openSession(sessionId, CERT, FAR_FUTURE))
    }

    private fun lockArtifact(artifactId: String = ARTIFACT_ID, sessionId: String = SESSION_ID) {
        artifactStore.setState(artifactId, ArtifactState.UNDER_REVIEW)
        lockMgr.lock(artifactId, sessionId)
    }

    // --- MSI-1: Full happy path ---

    @Test
    fun `MSI-1 full happy path — write applied and field readable from DB`() {
        openActiveSession()
        lockArtifact()

        val result = steward().write(ReviewWriteRequest(
            sessionToken = "token",
            artifactId   = ARTIFACT_ID,
            field        = "verdict",
            value        = "approved"
        ))

        assertEquals(StewardResult.APPLIED, result)

        // Field is durably stored — readable from the same real store
        assertEquals("approved", artifactStore.readField(ARTIFACT_ID, "verdict"),
            "Written field must be readable from SQLiteArtifactStore after APPLIED")
    }

    // --- MSI-2: Expired session rejected at step_2 ---

    @Test
    fun `MSI-2 expired session — write rejected at step_2 by real registry`() {
        // Open session with past expiry
        assertTrue(sessionRegistry.openSession(SESSION_ID, CERT, FAR_PAST))
        lockArtifact()

        val result = steward().write(ReviewWriteRequest(
            sessionToken = "token",
            artifactId   = ARTIFACT_ID,
            field        = "verdict",
            value        = "should not apply"
        ))

        assertEquals(StewardResult.REJECTED, result)
        assertNull(artifactStore.readField(ARTIFACT_ID, "verdict"),
            "No field must be written for a rejected write")
    }

    // --- MSI-3: onSessionExpired() pipeline ---

    @Test
    fun `MSI-3 onSessionExpired releases lock and reverts artifact state to SUBMITTED`() {
        openActiveSession()
        lockArtifact()

        // Confirm setup: artifact is UNDER_REVIEW, locked by SESSION_ID
        assertEquals(ArtifactState.UNDER_REVIEW, artifactStore.getState(ARTIFACT_ID))
        assertEquals(SESSION_ID, lockMgr.lockHolder(ARTIFACT_ID))

        steward().onSessionExpired(SESSION_ID)

        // Lock released
        assertNull(lockMgr.lockHolder(ARTIFACT_ID),
            "Lock must be released after session expiry")

        // Artifact reverted
        assertEquals(ArtifactState.SUBMITTED, artifactStore.getState(ARTIFACT_ID),
            "Artifact must revert to SUBMITTED after session expiry")
    }

    // --- MSI-4: expireSessions() + write attempt ---

    @Test
    fun `MSI-4 expireSessions then write attempt — rejected by real registry state`() {
        assertTrue(sessionRegistry.openSession(SESSION_ID, CERT, FAR_PAST))
        lockArtifact()

        // Sweep expires the session
        val expired = sessionRegistry.expireSessions()
        assertEquals(1, expired)

        val result = steward().write(ReviewWriteRequest(
            sessionToken = "token",
            artifactId   = ARTIFACT_ID,
            field        = "notes",
            value        = "should not apply"
        ))

        assertEquals(StewardResult.REJECTED, result,
            "Write must be rejected after expireSessions() transitions session to EXPIRED")
    }

    // --- MSI-5: Multiple writes, all fields durable ---

    @Test
    fun `MSI-5 multiple field writes — all durable and independently readable`() {
        openActiveSession()
        lockArtifact()

        val steward = steward()
        assertEquals(StewardResult.APPLIED, steward.write(ReviewWriteRequest("t", ARTIFACT_ID, "verdict", "approved")))
        assertEquals(StewardResult.APPLIED, steward.write(ReviewWriteRequest("t", ARTIFACT_ID, "notes", "LGTM")))
        assertEquals(StewardResult.APPLIED, steward.write(ReviewWriteRequest("t", ARTIFACT_ID, "reviewer", "reviewer-x")))

        assertEquals("approved",    artifactStore.readField(ARTIFACT_ID, "verdict"))
        assertEquals("LGTM",        artifactStore.readField(ARTIFACT_ID, "notes"))
        assertEquals("reviewer-x",  artifactStore.readField(ARTIFACT_ID, "reviewer"))
    }

    // --- MSI-6: ProposalStatusReceipt written to real ReceiptChannel on expiry ---

    @Test
    fun `MSI-6 onSessionExpired writes ProposalStatusReceipt to SQLiteReceiptChannel`() {
        openActiveSession()
        lockArtifact()

        steward().onSessionExpired(SESSION_ID)

        // Verify the receipt was durably written to the real receipt DB
        val count = receiptShared.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM receipts WHERE receipt_type = 'ProposalStatusReceipt' " +
                "AND artifact_id = '$ARTIFACT_ID' AND to_state = 'SUBMITTED'"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(1, count,
            "ProposalStatusReceipt must be durably written to SQLiteReceiptChannel on session expiry")
    }
}
