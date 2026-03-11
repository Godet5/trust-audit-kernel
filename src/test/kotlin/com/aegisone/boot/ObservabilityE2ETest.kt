package com.aegisone.boot

import com.aegisone.broker.AgentRole
import com.aegisone.broker.GrantAuthority
import com.aegisone.db.ReviewDbBootstrap
import com.aegisone.db.SQLiteArtifactLockManager
import com.aegisone.db.SQLiteArtifactStore
import com.aegisone.db.SQLiteAuditFailureChannel
import com.aegisone.db.SQLiteAuthorityDecisionChannel
import com.aegisone.db.SQLiteBootstrap
import com.aegisone.db.SQLiteReceiptChannel
import com.aegisone.db.SQLiteSessionRegistry
import com.aegisone.db.SQLiteSystemEventChannel
import com.aegisone.execution.AuditRecord
import com.aegisone.zonea.FileBackedVersionFloorProvider
import com.aegisone.zonea.FileBackedZoneAStore
import com.aegisone.zonea.GrantRecord
import com.aegisone.zonea.ManifestRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import com.aegisone.db.SharedConnection
import com.aegisone.execution.ConflictAlert
import com.aegisone.execution.ConflictChannel
import com.aegisone.invariants.TestAuthorityDecisionChannel
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end observability tests.
 *
 * Proves that every authority decision and recovery action is durably observable
 * and attributable without changing the execution invariants.
 *
 * All 97 prior tests pass without any observability wiring — the new channels
 * are optional parameters with null defaults throughout. These tests prove the
 * wired paths produce the correct durable records.
 *
 * OBS-1: Boot success + grant decisions — BootVerified in system_events,
 *         GrantIssued + GrantDenied in authority_decisions with correct fields
 * OBS-2: Boot failure — BootFailed in system_events with step and reason
 * OBS-3: Recovery telemetry — RecoveryCompleted in system_events with
 *         reconciliation status, expired_sessions, and ready_for_active
 * OBS-4: Broker state transition — BrokerStateChanged in system_events with
 *         from_state, to_state, manifest_version, reason on floor mismatch
 */
class ObservabilityE2ETest {

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

    private fun provisionZoneA(store: FileBackedZoneAStore) {
        val record = ManifestRecord(
            version      = 1,
            createdAt    = System.currentTimeMillis() - 1_000L,
            schemaValid  = true,
            signatureHex = ManifestRecord.bytesToHex(PLATFORM_KEY),
            grants = listOf(
                GrantRecord(CAPABILITY, AgentRole.EXECUTOR.name, GrantAuthority.SYSTEM_POLICY.name)
            ),
            agents = mapOf("exec-01" to AgentRole.EXECUTOR.name)
        )
        assertTrue(store.provision(record, PLATFORM_KEY, VERSION_FLOOR))
    }

    // --- OBS-1: boot decisions recorded in authority_decisions and system_events ---

    @Test
    fun `OBS-1 boot + grant decisions — BootVerified and GrantIssued and GrantDenied all durable`() {
        val store         = FileBackedZoneAStore(zoneADir())
        val floorProvider = FileBackedVersionFloorProvider(zoneADir())
        provisionZoneA(store)

        val receiptChannel  = SQLiteReceiptChannel(receiptShared)
        val decisionChannel = SQLiteAuthorityDecisionChannel(receiptShared)
        val eventChannel    = SQLiteSystemEventChannel(receiptShared)

        // Boot with all observability channels wired
        val bootResult = BootOrchestrator(
            zoneAStore              = store,
            versionFloorProvider    = floorProvider,
            receiptChannel          = receiptChannel,
            systemEventChannel      = eventChannel,
            authorityDecisionChannel = decisionChannel
        ).boot()
        assertTrue(bootResult is BootResult.Active, "Boot must succeed")
        val broker = (bootResult as BootResult.Active).broker

        // Issue one grant that should succeed
        val issued = broker.issueGrant(CAPABILITY, AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertTrue(issued != null, "Grant must be issued")

        // Issue one grant that should be denied (capability not in manifest)
        val denied = broker.issueGrant("UNKNOWN_CAP", AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertTrue(denied == null, "Grant must be denied for unknown capability")

        // Verify: BootVerified event with correct manifest version
        val bootEvents = receiptShared.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT manifest_version FROM system_events WHERE event_type = 'BootVerified'"
            ).use { rs ->
                if (rs.next()) rs.getInt("manifest_version") else -1
            }
        }
        assertEquals(1, bootEvents,
            "BootVerified event must record manifest_version = 1")

        // Verify: GrantIssued with correct capability and role
        val issuedCount = receiptShared.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM authority_decisions " +
                "WHERE decision_type = 'GrantIssued' " +
                "AND capability_name = '$CAPABILITY' " +
                "AND target_role = 'EXECUTOR'"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(1, issuedCount,
            "GrantIssued must be recorded for the successful grant")

        // Verify: GrantDenied with reason GRANT_NOT_IN_MANIFEST
        val deniedRow = receiptShared.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT capability_name, reason, manifest_version FROM authority_decisions " +
                "WHERE decision_type = 'GrantDenied'"
            ).use { rs ->
                if (rs.next()) Triple(rs.getString(1), rs.getString(2), rs.getInt(3))
                else null
            }
        }
        assertTrue(deniedRow != null, "GrantDenied must be recorded for the denied grant")
        assertEquals("UNKNOWN_CAP", deniedRow!!.first)
        assertEquals("GRANT_NOT_IN_MANIFEST", deniedRow.second)
        assertEquals(1, deniedRow.third, "manifest_version must be attributed on denial")
    }

    // --- OBS-2: boot failure recorded ---

    @Test
    fun `OBS-2 boot failure — BootFailed event recorded with step and reason`() {
        val store         = FileBackedZoneAStore(zoneADir())  // not provisioned
        val floorProvider = FileBackedVersionFloorProvider(zoneADir())
        val receiptChannel = SQLiteReceiptChannel(receiptShared)
        val eventChannel   = SQLiteSystemEventChannel(receiptShared)

        val bootResult = BootOrchestrator(
            zoneAStore           = store,
            versionFloorProvider = floorProvider,
            receiptChannel       = receiptChannel,
            systemEventChannel   = eventChannel,
            authorityDecisionChannel = TestAuthorityDecisionChannel()
        ).boot()
        assertTrue(bootResult is BootResult.Failed, "Boot must fail on unprovisioned Zone A")

        val failedEvent = receiptShared.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT step, reason FROM system_events WHERE event_type = 'BootFailed'"
            ).use { rs ->
                if (rs.next()) Pair(rs.getString("step"), rs.getString("reason")) else null
            }
        }
        assertTrue(failedEvent != null, "BootFailed event must be recorded")
        assertEquals("ZONE_A_ACCESS", failedEvent!!.first,
            "BootFailed event must carry the correct failure step")
        assertTrue(failedEvent.second.contains("ZONE_A_ACCESS"),
            "BootFailed reason must identify the failure point")
    }

    // --- OBS-3: recovery telemetry ---

    @Test
    fun `OBS-3 recovery with orphaned PENDING — RecoveryCompleted event records REPAIRED status`() {
        // Inject an orphaned PENDING to force REPAIRED result
        val auditChannel = SQLiteAuditFailureChannel(receiptShared)
        assertTrue(auditChannel.write(AuditRecord.Pending(
            receiptId = "orphan-obs3", capabilityName = "WRITE_NOTE",
            agentId = "exec-obs", sessionId = "sess-obs", sequenceNumber = 1
        )))

        val receiptChannel = SQLiteReceiptChannel(receiptShared)
        val eventChannel   = SQLiteSystemEventChannel(receiptShared)

        val result = StartupRecovery(
            receiptShared      = receiptShared,
            sessionRegistry    = sessionRegistry,
            artifactStore      = artifactStore,
            lockManager        = lockMgr,
            receiptChannel     = receiptChannel,
            systemEventChannel = eventChannel
        ).run()

        assertEquals("REPAIRED", result.reconciliation.name)
        assertTrue(result.readyForActive)

        // Verify: RecoveryCompleted event with correct fields
        val eventRow = receiptShared.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT reconciliation_status, expired_sessions, ready_for_active " +
                "FROM system_events WHERE event_type = 'RecoveryCompleted'"
            ).use { rs ->
                if (rs.next()) Triple(rs.getString(1), rs.getInt(2), rs.getInt(3))
                else null
            }
        }
        assertTrue(eventRow != null, "RecoveryCompleted event must be recorded")
        assertEquals("REPAIRED", eventRow!!.first,
            "RecoveryCompleted must carry the reconciliation status")
        assertEquals(0, eventRow.second,
            "expired_sessions must be 0 (no stale sessions in this test)")
        assertEquals(1, eventRow.third,
            "ready_for_active must be 1 (true) for REPAIRED result")
    }

    // --- OBS-4: broker state transition durably recorded ---

    @Test
    fun `OBS-4 floor mismatch — BrokerStateChanged event persisted with correct fields`() {
        val store         = FileBackedZoneAStore(zoneADir())
        val floorProvider = FileBackedVersionFloorProvider(zoneADir())
        provisionZoneA(store)

        val receiptChannel  = SQLiteReceiptChannel(receiptShared)
        val decisionChannel = SQLiteAuthorityDecisionChannel(receiptShared)
        val eventChannel    = SQLiteSystemEventChannel(receiptShared)

        // Boot with all observability channels wired
        val bootResult = BootOrchestrator(
            zoneAStore              = store,
            versionFloorProvider    = floorProvider,
            receiptChannel          = receiptChannel,
            systemEventChannel      = eventChannel,
            authorityDecisionChannel = decisionChannel
        ).boot()
        assertTrue(bootResult is BootResult.Active, "Boot must succeed")
        val broker = (bootResult as BootResult.Active).broker

        // Raise the floor above the manifest version (1) to trigger ACTIVE→RESTRICTED
        assertTrue(floorProvider.raise(5), "Floor raise must succeed")

        // Attempt a grant — should be denied and emit BrokerStateChanged
        val result = broker.issueGrant(CAPABILITY, AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertTrue(result == null, "Grant must be denied on floor mismatch")

        // Verify: BrokerStateChanged event with correct fields in system_events
        val eventRow = receiptShared.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT from_state, to_state, manifest_version, reason " +
                "FROM system_events WHERE event_type = 'BrokerStateChanged'"
            ).use { rs ->
                if (rs.next()) object {
                    val fromState = rs.getString("from_state")
                    val toState = rs.getString("to_state")
                    val manifestVersion = rs.getInt("manifest_version")
                    val reason = rs.getString("reason")
                }
                else null
            }
        }
        assertTrue(eventRow != null, "BrokerStateChanged event must be persisted in system_events")
        assertEquals("ACTIVE", eventRow!!.fromState,
            "from_state must be ACTIVE")
        assertEquals("RESTRICTED", eventRow.toState,
            "to_state must be RESTRICTED")
        assertEquals(1, eventRow.manifestVersion,
            "manifest_version must be the broker's verified version (1)")
        assertTrue(eventRow.reason.contains("MANIFEST_VERSION_BELOW_FLOOR"),
            "reason must identify the floor mismatch trigger")
    }
}
