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
import kotlin.test.assertTrue

/**
 * End-to-end review slice integration tests.
 *
 * Proves the second real path through the system:
 *   BootOrchestrator (FileBackedZoneAStore) →
 *   ReviewOrchestrator (CapabilityBroker + SQLiteSessionRegistry) →
 *   MemorySteward (SQLiteArtifactStore + SQLiteArtifactLockManager +
 *                  SQLiteReceiptChannel)
 *
 * The key distinction from MemoryStewardIntegrationTest: those tests open
 * sessions directly on the registry with no broker authority check.
 * This slice adds the missing broker gate — the composition between the
 * broker authority subsystem and the review persistence subsystem.
 *
 * TokenVerifier remains a test double (FixedTokenVerifier). Crypto
 * verification is a production hardware boundary (KeyStore/Strongbox),
 * not a persistence boundary. The correct real boundary to prove here is
 * the broker authority → session registry → MemorySteward chain.
 *
 * RS-1: Full review slice — boot, open session, lock artifact, write,
 *        field persisted in real DB
 * RS-2: REVIEW_WRITE not in manifest — broker denies grant,
 *        session never opens
 */
class ReviewSliceE2ETest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var receiptConn: Connection
    private lateinit var reviewConn: Connection

    private lateinit var sessionRegistry: SQLiteSessionRegistry
    private lateinit var artifactStore: SQLiteArtifactStore
    private lateinit var lockMgr: SQLiteArtifactLockManager

    private val PLATFORM_KEY  = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    private val VERSION_FLOOR = 1
    private val CERT          = "cert-rs-e2e"
    private val SESSION_ID    = "sess-rs"
    private val ARTIFACT_ID   = "art-rs"
    private val FAR_FUTURE    = System.currentTimeMillis() + 3_600_000L

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

    /**
     * Provision Zone A. includeReviewWrite controls whether the REVIEW_WRITE
     * grant for MEMORY_STEWARD is in the manifest, allowing RS-2 to verify
     * the broker denies the capability when it is absent.
     */
    private fun provisionZoneA(store: FileBackedZoneAStore, includeReviewWrite: Boolean = true) {
        val grants = if (includeReviewWrite) {
            listOf(
                GrantRecord(
                    ReviewOrchestrator.REVIEW_WRITE_CAPABILITY,
                    AgentRole.MEMORY_STEWARD.name,
                    GrantAuthority.SYSTEM_POLICY.name
                )
            )
        } else {
            // Include a different capability so the manifest is otherwise valid
            listOf(
                GrantRecord("SOME_OTHER_CAP", AgentRole.EXECUTOR.name, GrantAuthority.SYSTEM_POLICY.name)
            )
        }
        val record = ManifestRecord(
            version      = 1,
            createdAt    = System.currentTimeMillis() - 1_000L,
            schemaValid  = true,
            signatureHex = ManifestRecord.bytesToHex(PLATFORM_KEY),
            grants       = grants,
            agents       = mapOf("steward-01" to AgentRole.MEMORY_STEWARD.name)
        )
        assertTrue(store.provision(record, PLATFORM_KEY, VERSION_FLOOR),
            "Zone A provisioning must succeed")
    }

    private fun lockArtifact() {
        artifactStore.setState(ARTIFACT_ID, ArtifactState.UNDER_REVIEW)
        lockMgr.lock(ARTIFACT_ID, SESSION_ID)
    }

    // --- RS-1: full review slice ---

    @Test
    fun `RS-1 full review slice — boot, open session, write, field persisted`() {
        val store         = FileBackedZoneAStore(zoneADir())
        val floorProvider = FileBackedVersionFloorProvider(zoneADir())
        provisionZoneA(store)

        val receiptChannel = SQLiteReceiptChannel(receiptConn)

        // Boot
        val bootResult = BootOrchestrator(store, floorProvider, receiptChannel).boot()
        assertTrue(bootResult is BootResult.Active, "Boot must succeed with valid provisioned manifest")
        val broker = (bootResult as BootResult.Active).broker

        // Open review session — broker authorizes via manifest grant list
        val orchestrator = ReviewOrchestrator(
            broker           = broker,
            sessionRegistry  = sessionRegistry,
            artifactStore    = artifactStore,
            lockManager      = lockMgr,
            receiptChannel   = receiptChannel,
            fieldScopePolicy = TestFieldScopePolicy(setOf("verdict", "notes", "reviewer"))
        )
        val sessionResult = orchestrator.openReviewSession(
            sessionId       = SESSION_ID,
            certFingerprint = CERT,
            expiryMs        = FAR_FUTURE,
            tokenVerifier   = FixedTokenVerifier(SESSION_ID, CERT)
        )
        assertTrue(sessionResult is ReviewSessionResult.Opened,
            "Review session must open: broker ACTIVE + REVIEW_WRITE in manifest")
        val memorySteward = (sessionResult as ReviewSessionResult.Opened).memorySteward

        // Set up artifact as UNDER_REVIEW, locked by this session
        lockArtifact()

        // Apply write through MemorySteward — all 7 steps
        val writeResult = memorySteward.write(
            ReviewWriteRequest(
                sessionToken = "token",
                artifactId   = ARTIFACT_ID,
                field        = "verdict",
                value        = "approved"
            )
        )
        assertEquals(StewardResult.APPLIED, writeResult,
            "Write must be APPLIED through the full live stack")

        // Field is durable — readable from the real store
        assertEquals("approved", artifactStore.readField(ARTIFACT_ID, "verdict"),
            "Written field must be readable from SQLiteArtifactStore after APPLIED")
    }

    // --- RS-2: REVIEW_WRITE absent from manifest — session denied at Gate 1 ---

    @Test
    fun `RS-2 REVIEW_WRITE not in manifest — broker denies grant and session never opens`() {
        val store         = FileBackedZoneAStore(zoneADir())
        val floorProvider = FileBackedVersionFloorProvider(zoneADir())
        // Manifest is valid but does not include REVIEW_WRITE for MEMORY_STEWARD
        provisionZoneA(store, includeReviewWrite = false)

        val receiptChannel = SQLiteReceiptChannel(receiptConn)

        // Boot succeeds — broker reaches ACTIVE state
        val bootResult = BootOrchestrator(store, floorProvider, receiptChannel).boot()
        assertTrue(bootResult is BootResult.Active,
            "Boot must succeed even when REVIEW_WRITE is absent from manifest")
        val broker = (bootResult as BootResult.Active).broker

        // ReviewOrchestrator Gate 1 blocks: issueGrant returns null (GRANT_NOT_IN_MANIFEST)
        val orchestrator = ReviewOrchestrator(
            broker           = broker,
            sessionRegistry  = sessionRegistry,
            artifactStore    = artifactStore,
            lockManager      = lockMgr,
            receiptChannel   = receiptChannel,
            fieldScopePolicy = TestFieldScopePolicy(setOf("verdict"))
        )
        val sessionResult = orchestrator.openReviewSession(
            sessionId       = SESSION_ID,
            certFingerprint = CERT,
            expiryMs        = FAR_FUTURE,
            tokenVerifier   = FixedTokenVerifier(SESSION_ID, CERT)
        )
        assertTrue(sessionResult is ReviewSessionResult.Denied,
            "Session must be denied when REVIEW_WRITE is not in the manifest grant list")

        // No session was opened in the registry
        val entry = sessionRegistry.lookup(SESSION_ID)
        assertEquals(null, entry,
            "No session must be created in the registry when the broker denies the grant")
    }
}
