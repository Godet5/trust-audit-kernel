package com.aegisone.boot

import com.aegisone.broker.AgentRole
import com.aegisone.broker.GrantAuthority
import com.aegisone.db.ReviewDbBootstrap
import com.aegisone.db.SQLiteArtifactLockManager
import com.aegisone.db.SQLiteArtifactStore
import com.aegisone.db.SQLiteBootstrap
import com.aegisone.db.SQLiteReceiptChannel
import com.aegisone.db.SQLiteSessionRegistry
import com.aegisone.invariants.FixedTokenVerifier
import com.aegisone.invariants.TestFieldScopePolicy
import com.aegisone.review.ArtifactState
import com.aegisone.review.MemorySteward
import com.aegisone.review.ReviewWriteRequest
import com.aegisone.review.StewardResult
import com.aegisone.zonea.FileBackedVersionFloorProvider
import com.aegisone.zonea.FileBackedZoneAStore
import com.aegisone.zonea.GrantRecord
import com.aegisone.zonea.ManifestRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end session-expiry recovery tests.
 *
 * Proves the live recovery loop through all real backends:
 *   ReviewOrchestrator.openReviewSession() →
 *   MemorySteward.write() (state mutation) →
 *   ExpiryCoordinator.sweep() (triggers expiry + cleanup) →
 *   lock released, artifact reverted to SUBMITTED,
 *   ProposalStatusReceipt durable in receipt DB,
 *   subsequent write rejected by real registry
 *
 * The key integration point: ExpiryCoordinator.expireSessionsAndGetIds()
 * atomically transitions sessions from ACTIVE → EXPIRED and returns the IDs,
 * allowing the coordinator to call MemorySteward.onSessionExpired() for each
 * without a TOCTOU race between the SELECT and UPDATE.
 *
 * SE-1: Full recovery — session opens, write applied, session expires,
 *        ExpiryCoordinator sweeps: lock released, artifact → SUBMITTED,
 *        ProposalStatusReceipt written durably
 * SE-2: Post-expiry rejection — after recovery, subsequent write through
 *        MemorySteward is rejected by the real session registry
 */
class SessionExpiryE2ETest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var receiptConn: Connection
    private lateinit var reviewConn: Connection

    private lateinit var sessionRegistry: SQLiteSessionRegistry
    private lateinit var artifactStore: SQLiteArtifactStore
    private lateinit var lockMgr: SQLiteArtifactLockManager

    private val PLATFORM_KEY  = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    private val VERSION_FLOOR = 1
    private val CERT          = "cert-se-e2e"
    private val SESSION_ID    = "sess-se"
    private val ARTIFACT_ID   = "art-se"

    // FAR_FUTURE is used as the initial session expiry so writes can succeed.
    // The coordinator is called with FAR_FUTURE + 1 to force expiry without
    // advancing the system clock.
    private val FAR_FUTURE = System.currentTimeMillis() + 3_600_000L

    @BeforeEach
    fun setup() {
        receiptConn = SQLiteBootstrap.openAndInitialize(File(tempDir, "receipts.db").absolutePath)
        reviewConn  = ReviewDbBootstrap.openAndInitialize(File(tempDir, "review.db").absolutePath)
        sessionRegistry = SQLiteSessionRegistry(reviewConn)
        artifactStore   = SQLiteArtifactStore(reviewConn)
        lockMgr         = SQLiteArtifactLockManager(reviewConn)
    }

    @AfterEach
    fun teardown() {
        runCatching { receiptConn.close() }
        runCatching { reviewConn.close() }
    }

    private fun zoneADir() = File(tempDir, "zoneA")

    private fun bootAndOpenSession(): MemorySteward {
        val store         = FileBackedZoneAStore(zoneADir())
        val floorProvider = FileBackedVersionFloorProvider(zoneADir())

        val record = ManifestRecord(
            version      = 1,
            createdAt    = System.currentTimeMillis() - 1_000L,
            schemaValid  = true,
            signatureHex = ManifestRecord.bytesToHex(PLATFORM_KEY),
            grants = listOf(
                GrantRecord(
                    ReviewOrchestrator.REVIEW_WRITE_CAPABILITY,
                    AgentRole.MEMORY_STEWARD.name,
                    GrantAuthority.SYSTEM_POLICY.name
                )
            ),
            agents = mapOf("steward-01" to AgentRole.MEMORY_STEWARD.name)
        )
        assertTrue(store.provision(record, PLATFORM_KEY, VERSION_FLOOR))

        val receiptChannel = SQLiteReceiptChannel(receiptConn)
        val bootResult = BootOrchestrator(store, floorProvider, receiptChannel).boot()
        assertTrue(bootResult is BootResult.Active, "Boot must succeed")
        val broker = (bootResult as BootResult.Active).broker

        val orchestrator = ReviewOrchestrator(
            broker           = broker,
            sessionRegistry  = sessionRegistry,
            artifactStore    = artifactStore,
            lockManager      = lockMgr,
            receiptChannel   = receiptChannel,
            fieldScopePolicy = TestFieldScopePolicy(setOf("verdict", "notes"))
        )
        val sessionResult = orchestrator.openReviewSession(
            sessionId       = SESSION_ID,
            certFingerprint = CERT,
            expiryMs        = FAR_FUTURE,
            tokenVerifier   = FixedTokenVerifier(SESSION_ID, CERT)
        )
        assertTrue(sessionResult is ReviewSessionResult.Opened,
            "Review session must open after boot")
        return (sessionResult as ReviewSessionResult.Opened).memorySteward
    }

    private fun lockArtifact() {
        artifactStore.setState(ARTIFACT_ID, ArtifactState.UNDER_REVIEW)
        lockMgr.lock(ARTIFACT_ID, SESSION_ID)
    }

    // --- SE-1: full recovery lifecycle ---

    @Test
    fun `SE-1 full recovery — write applied, session expires, lock released, artifact reverted, receipt written`() {
        val memorySteward = bootAndOpenSession()
        lockArtifact()

        // Apply a write — session is active with FAR_FUTURE expiry, so write succeeds
        val writeResult = memorySteward.write(
            ReviewWriteRequest("token", ARTIFACT_ID, "verdict", "approved")
        )
        assertEquals(StewardResult.APPLIED, writeResult, "Write must be APPLIED before expiry")
        assertEquals("approved", artifactStore.readField(ARTIFACT_ID, "verdict"),
            "Field must be persisted")

        // Confirm setup: artifact is UNDER_REVIEW, locked by SESSION_ID
        assertEquals(ArtifactState.UNDER_REVIEW, artifactStore.getState(ARTIFACT_ID))
        assertEquals(SESSION_ID, lockMgr.lockHolder(ARTIFACT_ID))

        // Force expiry: sweep with now = FAR_FUTURE + 1 (session's expiry_ms == FAR_FUTURE)
        val coordinator = ExpiryCoordinator(sessionRegistry, memorySteward)
        val swept = coordinator.sweep(now = FAR_FUTURE + 1)
        assertEquals(1, swept, "ExpiryCoordinator must report one session recovered")

        // Lock released
        assertNull(lockMgr.lockHolder(ARTIFACT_ID),
            "Lock must be released after expiry sweep")

        // Artifact reverted to SUBMITTED
        assertEquals(ArtifactState.SUBMITTED, artifactStore.getState(ARTIFACT_ID),
            "Artifact must revert to SUBMITTED after expiry sweep")

        // ProposalStatusReceipt written durably to receipt DB
        val receiptCount = receiptConn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM receipts " +
                "WHERE receipt_type = 'ProposalStatusReceipt' " +
                "AND artifact_id = '$ARTIFACT_ID' " +
                "AND to_state = 'SUBMITTED'"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(1, receiptCount,
            "ProposalStatusReceipt must be durably written to SQLiteReceiptChannel on expiry")
    }

    // --- SE-2: post-expiry write rejected by real registry state ---

    @Test
    fun `SE-2 post-expiry write rejected — real registry shows session EXPIRED`() {
        val memorySteward = bootAndOpenSession()
        lockArtifact()

        // Write succeeds while session is active
        assertEquals(StewardResult.APPLIED,
            memorySteward.write(ReviewWriteRequest("token", ARTIFACT_ID, "notes", "first note")))

        // Sweep with force-expiry
        val coordinator = ExpiryCoordinator(sessionRegistry, memorySteward)
        assertEquals(1, coordinator.sweep(now = FAR_FUTURE + 1))

        // After expiry, the session is EXPIRED in the registry.
        // MemorySteward step_2 reads the real registry: active=false → rejects.
        // Note: lock was released by onSessionExpired(), so step_4 would reject too,
        // but step_2 fires first. Both would reject — the test verifies step_2 wins.
        val postExpiry = memorySteward.write(
            ReviewWriteRequest("token", ARTIFACT_ID, "notes", "should not apply")
        )
        assertEquals(StewardResult.REJECTED, postExpiry,
            "Write must be REJECTED after session has been expired by real ExpiryCoordinator")

        // Field value remains the pre-expiry write, not the rejected one
        assertEquals("first note", artifactStore.readField(ARTIFACT_ID, "notes"),
            "Rejected write must not mutate the persisted field value")
    }
}
