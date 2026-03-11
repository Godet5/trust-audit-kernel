package com.aegisone.inspect

import com.aegisone.boot.BootOrchestrator
import com.aegisone.boot.BootResult
import com.aegisone.boot.StartupRecovery
import com.aegisone.broker.AgentRole
import com.aegisone.broker.GrantAuthority
import com.aegisone.db.ReviewDbBootstrap
import com.aegisone.db.SQLiteArtifactLockManager
import com.aegisone.db.SQLiteArtifactStore
import com.aegisone.db.SQLiteAuditFailureChannel
import com.aegisone.db.SQLiteAuthorityDecisionChannel
import com.aegisone.db.SharedConnection
import com.aegisone.db.SQLiteBootstrap
import com.aegisone.db.SQLiteReceiptChannel
import com.aegisone.db.SQLiteSessionRegistry
import com.aegisone.db.SQLiteSystemEventChannel
import com.aegisone.execution.ActionExecutor
import com.aegisone.execution.ActionRequest
import com.aegisone.execution.AuditRecord
import com.aegisone.execution.ConflictAlert
import com.aegisone.execution.ConflictChannel
import com.aegisone.execution.ExecutionCoordinator
import com.aegisone.review.ArtifactState
import com.aegisone.zonea.FileBackedVersionFloorProvider
import com.aegisone.zonea.FileBackedZoneAStore
import com.aegisone.zonea.GrantRecord
import com.aegisone.zonea.ManifestRecord
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
 * End-to-end tests for SystemInspector.
 *
 * Proves that the operator tooling returns correct structured data against
 * known system state — without raw SQL, without bypassing production channels,
 * and without mutating state.
 *
 * OI-1: Decisions and system events — recentDecisions() and recentSystemEvents()
 *         return correct rows after boot + grant + denial.
 *
 * OI-2: Action receipts and audit failures — recentReceipts() shows ActionReceipt
 *         after successful execution; recentAuditFailures() shows the PENDING record.
 *
 * OI-3: Sessions, locks, and artifacts — sessions() shows ACTIVE session;
 *         currentLocks() shows lock with sessionState=ACTIVE; artifacts() shows
 *         lockHolder set to the session that holds the lock.
 *
 * OI-4: Ghost lock detection — currentLocks() shows sessionState=null when the
 *         lock references a session_id with no sessions row.
 *
 * OI-5: Recovery summary — clean state after consistent boot produces
 *         isClean=true, violationA/B/C all 0.
 *
 * OI-6: Recovery summary — injected violations are counted correctly before
 *         recovery runs; violationA/B/C > 0, isClean=false.
 *
 * OI-7: requiresHumanReview — injected UnauditedIrreversible record causes
 *         recoverySummary().requiresHumanReview=true.
 *
 * OI-8: Without reviewConn — sessions/locks/artifacts return emptyList();
 *         summary fields that require reviewConn are -1.
 */
class OperatorInspectorE2ETest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var receiptShared: SharedConnection
    private lateinit var reviewShared: SharedConnection

    private lateinit var sessionRegistry: SQLiteSessionRegistry
    private lateinit var artifactStore: SQLiteArtifactStore
    private lateinit var lockMgr: SQLiteArtifactLockManager

    private val PLATFORM_KEY  = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    private val VERSION_FLOOR = 1
    private val CAPABILITY    = "WRITE_NOTE"
    private val AGENT_ID      = "exec-inspector"
    private val SESSION_ID    = "sess-inspector"
    private val noOpConflict = object : ConflictChannel { override fun alert(alert: ConflictAlert) = true }

    @BeforeEach
    fun setup() {
        receiptShared = SQLiteBootstrap.openAndInitialize(File(tempDir, "receipts.db").absolutePath)
        reviewShared  = ReviewDbBootstrap.openAndInitialize(File(tempDir, "review.db").absolutePath)
        sessionRegistry = SQLiteSessionRegistry(reviewShared)
        artifactStore   = SQLiteArtifactStore(reviewShared)
        lockMgr         = SQLiteArtifactLockManager(reviewShared)
    }

    @AfterEach
    fun teardown() {
        runCatching { receiptShared.close() }
        runCatching { reviewShared.close() }
    }

    private fun zoneADir() = File(tempDir, "zoneA")

    private fun provisionAndBoot(): com.aegisone.broker.CapabilityBroker {
        val store         = FileBackedZoneAStore(zoneADir())
        val floorProvider = FileBackedVersionFloorProvider(zoneADir())
        val record = ManifestRecord(
            version      = 1,
            createdAt    = System.currentTimeMillis() - 1_000L,
            schemaValid  = true,
            signatureHex = ManifestRecord.bytesToHex(PLATFORM_KEY),
            grants       = listOf(GrantRecord(CAPABILITY, AgentRole.EXECUTOR.name, GrantAuthority.SYSTEM_POLICY.name)),
            agents       = mapOf(AGENT_ID to AgentRole.EXECUTOR.name)
        )
        assertTrue(store.provision(record, PLATFORM_KEY, VERSION_FLOOR))

        val receiptChannel  = SQLiteReceiptChannel(receiptShared)
        val decisionChannel = SQLiteAuthorityDecisionChannel(receiptShared)
        val eventChannel    = SQLiteSystemEventChannel(receiptShared)

        val bootResult = BootOrchestrator(
            zoneAStore               = store,
            versionFloorProvider     = floorProvider,
            receiptChannel           = receiptChannel,
            systemEventChannel       = eventChannel,
            authorityDecisionChannel = decisionChannel
        ).boot()
        assertTrue(bootResult is BootResult.Active, "Boot must succeed")
        return (bootResult as BootResult.Active).broker
    }

    // --- OI-1: Decisions and system events ---

    @Test
    fun `OI-1 recentDecisions and recentSystemEvents return correct rows after boot and grants`() {
        val broker = provisionAndBoot()

        // One successful grant, one denied grant
        val issued = broker.issueGrant(CAPABILITY, AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertNotNull(issued, "Grant must be issued")
        val denied = broker.issueGrant("UNKNOWN_CAP", AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertNull(denied, "Grant must be denied for unknown capability")

        val inspector = SystemInspector(receiptShared, reviewShared)

        // Decisions
        val decisions = inspector.recentDecisions()
        assertTrue(decisions.isNotEmpty(), "recentDecisions must not be empty")
        val issuedRow = decisions.find { it.decisionType == "GrantIssued" && it.capabilityName == CAPABILITY }
        assertNotNull(issuedRow, "GrantIssued row must appear in decisions")
        assertEquals("EXECUTOR", issuedRow!!.targetRole)
        assertEquals("SYSTEM_POLICY", issuedRow.authority)
        assertEquals(1, issuedRow.manifestVersion)
        assertNull(issuedRow.reason, "GrantIssued must not carry a denial reason")

        val deniedRow = decisions.find { it.decisionType == "GrantDenied" && it.capabilityName == "UNKNOWN_CAP" }
        assertNotNull(deniedRow, "GrantDenied row must appear in decisions")
        assertEquals("GRANT_NOT_IN_MANIFEST", deniedRow!!.reason)
        assertEquals(1, deniedRow.manifestVersion)

        // System events
        val events = inspector.recentSystemEvents()
        val bootEvent = events.find { it.eventType == "BootVerified" }
        assertNotNull(bootEvent, "BootVerified event must appear in system events")
        assertEquals(1, bootEvent!!.manifestVersion)
        assertNull(bootEvent.step, "BootVerified must not carry a failure step")
    }

    // --- OI-2: Action receipts and audit failures ---

    @Test
    fun `OI-2 recentReceipts shows ActionReceipt and recentAuditFailures shows PENDING after execute`() {
        provisionAndBoot()

        val receiptChannel = SQLiteReceiptChannel(receiptShared)
        val auditChannel   = SQLiteAuditFailureChannel(receiptShared)

        val coordinator = ExecutionCoordinator(
            auditFailureChannel = auditChannel,
            receiptChannel      = receiptChannel,
            executor            = object : ActionExecutor {
                override fun execute(request: ActionRequest) = true
            },
            conflictChannel     = noOpConflict
        )
        coordinator.execute(ActionRequest(CAPABILITY, AGENT_ID, SESSION_ID))

        val inspector = SystemInspector(receiptShared, reviewShared)

        // Receipts
        val receipts = inspector.recentReceipts()
        val actionReceipt = receipts.find { it.receiptType == "ActionReceipt" && it.capabilityName == CAPABILITY }
        assertNotNull(actionReceipt, "ActionReceipt must appear in recentReceipts")
        assertEquals("SUCCESS", actionReceipt!!.status)
        assertEquals(AGENT_ID, actionReceipt.agentId)
        assertEquals(SESSION_ID, actionReceipt.sessionId)
        assertNotNull(actionReceipt.receiptId, "ActionReceipt must carry a receipt_id")

        // Audit failures — PENDING record written before execution
        val auditFailures = inspector.recentAuditFailures()
        val pendingRow = auditFailures.find { it.recordType == "Pending" && it.capabilityName == CAPABILITY }
        assertNotNull(pendingRow, "Pending audit record must appear in recentAuditFailures")
        assertEquals(AGENT_ID, pendingRow!!.agentId)
    }

    // --- OI-3: Sessions, locks, and artifacts ---

    @Test
    fun `OI-3 sessions shows ACTIVE session locks shows sessionState and artifacts shows lockHolder`() {
        val inspector = SystemInspector(receiptShared, reviewShared)

        // Open a session
        assertTrue(sessionRegistry.openSession("sess-oi3", "fp-oi3", Long.MAX_VALUE))

        // Create an artifact, lock it
        artifactStore.setState("art-oi3", ArtifactState.UNDER_REVIEW)
        lockMgr.lock("art-oi3", "sess-oi3")

        // Sessions
        val sessions = inspector.sessions()
        val sess = sessions.find { it.sessionId == "sess-oi3" }
        assertNotNull(sess, "sessions() must include the ACTIVE session")
        assertEquals("ACTIVE", sess!!.state)
        assertTrue(sess.isActive)
        assertEquals("fp-oi3", sess.certFingerprint)

        // Locks
        val locks = inspector.currentLocks()
        val lock = locks.find { it.artifactId == "art-oi3" }
        assertNotNull(lock, "currentLocks() must include the held lock")
        assertEquals("sess-oi3", lock!!.sessionId)
        assertEquals("ACTIVE", lock.sessionState, "sessionState must reflect the sessions row")

        // Artifacts
        val artifacts = inspector.artifacts()
        val art = artifacts.find { it.artifactId == "art-oi3" }
        assertNotNull(art, "artifacts() must include the artifact")
        assertEquals("UNDER_REVIEW", art!!.state)
        assertEquals("sess-oi3", art.lockHolder, "lockHolder must identify the session holding the lock")
    }

    // --- OI-4: Ghost lock detection ---

    @Test
    fun `OI-4 ghost lock — currentLocks shows sessionState null when session row is absent`() {
        // Inject a lock directly without creating a sessions row (ghost session)
        reviewShared.conn.prepareStatement(
            "INSERT INTO artifact_locks (artifact_id, session_id, acquired_at_ms) VALUES (?, ?, ?)"
        ).use { ps ->
            ps.setString(1, "art-oi4-ghost")
            ps.setString(2, "ghost-sess-oi4")
            ps.setLong(3, System.currentTimeMillis())
            ps.executeUpdate()
        }

        val inspector = SystemInspector(receiptShared, reviewShared)
        val locks = inspector.currentLocks()
        val ghostLock = locks.find { it.artifactId == "art-oi4-ghost" }
        assertNotNull(ghostLock, "currentLocks() must include the ghost lock")
        assertEquals("ghost-sess-oi4", ghostLock!!.sessionId)
        assertNull(ghostLock.sessionState, "sessionState must be null for a ghost lock (no sessions row)")
    }

    // --- OI-5: Recovery summary — clean state ---

    @Test
    fun `OI-5 recovery summary is clean when system is consistent`() {
        // Boot writes a BootVerified event; no violations injected
        provisionAndBoot()

        val inspector = SystemInspector(receiptShared, reviewShared)
        val summary = inspector.recoverySummary()

        assertNotNull(summary.lastBootResult, "lastBootResult must not be null after boot")
        assertEquals("BootVerified", summary.lastBootResult!!.eventType)
        assertEquals(0, summary.unresolvedIrreversible)
        assertEquals(0, summary.orphanedPending)
        assertEquals(0, summary.activeSessions, "No sessions opened — activeSessions must be 0")
        assertEquals(0, summary.violationA)
        assertEquals(0, summary.violationB)
        assertEquals(0, summary.violationC)
        assertTrue(summary.isClean)
        assertFalse(summary.requiresHumanReview)
    }

    // --- OI-6: Recovery summary — violations before recovery ---

    @Test
    fun `OI-6 recovery summary counts all three violation types before recovery runs`() {
        // Violation A: UNDER_REVIEW artifact with no lock
        artifactStore.setState("art-oi6-nolock", ArtifactState.UNDER_REVIEW)

        // Violation B: ghost lock (no sessions row)
        reviewShared.conn.prepareStatement(
            "INSERT INTO artifact_locks (artifact_id, session_id, acquired_at_ms) VALUES (?, ?, ?)"
        ).use { ps ->
            ps.setString(1, "art-oi6-ghost")
            ps.setString(2, "ghost-sess-oi6")
            ps.setLong(3, System.currentTimeMillis())
            ps.executeUpdate()
        }

        // Violation C: EXPIRED session with outstanding lock
        val now = System.currentTimeMillis()
        reviewShared.conn.prepareStatement(
            "INSERT INTO sessions (session_id, cert_fingerprint, state, created_at_ms, last_heartbeat_ms, expiry_ms) " +
            "VALUES (?, 'fp-oi6', 'EXPIRED', ?, ?, 1)"
        ).use { ps ->
            ps.setString(1, "sess-oi6-expired")
            ps.setLong(2, now)
            ps.setLong(3, now)
            ps.executeUpdate()
        }
        reviewShared.conn.prepareStatement(
            "INSERT INTO artifact_locks (artifact_id, session_id, acquired_at_ms) VALUES (?, ?, ?)"
        ).use { ps ->
            ps.setString(1, "art-oi6-expired")
            ps.setString(2, "sess-oi6-expired")
            ps.setLong(3, now)
            ps.executeUpdate()
        }

        val inspector = SystemInspector(receiptShared, reviewShared)
        val summary = inspector.recoverySummary()

        assertEquals(1, summary.violationA, "violationA must count the stranded UNDER_REVIEW artifact")
        // violationB counts ghost locks; art-oi6-ghost is a ghost (no sessions row)
        assertTrue(summary.violationB >= 1, "violationB must count the ghost lock")
        assertEquals(1, summary.violationC, "violationC must count the EXPIRED session with a lock")
        assertFalse(summary.isClean, "isClean must be false when violations exist")
        // P4: requiresHumanReview now includes cross-table violations (violationA/B/C > 0),
        // not just UnauditedIrreversible. These violations indicate state corruption that
        // an operator should be aware of before the system goes ACTIVE.
        assertTrue(summary.requiresHumanReview,
            "requiresHumanReview must be true when cross-table violations exist (P4 fix)")
    }

    // --- OI-7: requiresHumanReview ---

    @Test
    fun `OI-7 requiresHumanReview is true when UnauditedIrreversible record exists`() {
        // Inject an UnauditedIrreversible record directly (what the coordinator writes
        // when an irreversible action's receipt write fails)
        val auditChannel = SQLiteAuditFailureChannel(receiptShared)
        assertTrue(auditChannel.write(AuditRecord.UnauditedIrreversible(
            receiptId = "irrev-oi7",
            detail    = "RECEIPT_WRITE_FAILED for DELETE_ALL"
        )))

        val inspector = SystemInspector(receiptShared, reviewShared)
        val summary = inspector.recoverySummary()

        assertEquals(1, summary.unresolvedIrreversible,
            "unresolvedIrreversible must be 1 after injecting one UnauditedIrreversible record")
        assertTrue(summary.requiresHumanReview,
            "requiresHumanReview must be true when unresolvedIrreversible > 0")
        assertFalse(summary.isClean,
            "isClean must be false when requiresHumanReview is true")
    }

    // --- OI-8: Without reviewConn ---

    @Test
    fun `OI-8 inspector without reviewConn returns empty lists and minus-one for review fields`() {
        // Wire only receiptConn — review DB not provided
        val inspector = SystemInspector(receiptShared)

        assertTrue(inspector.sessions().isEmpty(),  "sessions() must return emptyList when reviewConn is absent")
        assertTrue(inspector.currentLocks().isEmpty(), "currentLocks() must return emptyList when reviewConn is absent")
        assertTrue(inspector.artifacts().isEmpty(),  "artifacts() must return emptyList when reviewConn is absent")

        val summary = inspector.recoverySummary()
        assertEquals(-1, summary.activeSessions, "activeSessions must be -1 when reviewConn is absent")
        assertEquals(-1, summary.violationA,     "violationA must be -1 when reviewConn is absent")
        assertEquals(-1, summary.violationB,     "violationB must be -1 when reviewConn is absent")
        assertEquals(-1, summary.violationC,     "violationC must be -1 when reviewConn is absent")
        // P3: isClean requires !isDegraded. -1 fields mean the review DB was unavailable,
        // so isDegraded=true and isClean=false. This is the correct behavior: "couldn't check"
        // must never render as "all clear" (AOC-1 fix).
        assertEquals(0, summary.unresolvedIrreversible)
        assertEquals(0, summary.orphanedPending)
        assertTrue(summary.isDegraded, "isDegraded must be true when review fields are -1 (P3 fix)")
        assertFalse(summary.isClean, "isClean must be false when degraded — 'couldn't check' is not 'all clear' (P3 fix)")
        // P4/RR-2: degraded mode forces requiresHumanReview — unknown state must never
        // be silently assumed safe. "Couldn't check" = "must review."
        assertTrue(summary.requiresHumanReview,
            "requiresHumanReview must be true when degraded — unknown state requires human review (P4/RR-2)")
    }
}
