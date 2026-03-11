package com.aegisone.boot

import com.aegisone.broker.AgentRole
import com.aegisone.broker.BrokerState
import com.aegisone.broker.GrantAuthority
import com.aegisone.db.SQLiteAuthorityDecisionChannel
import com.aegisone.db.SQLiteBootstrap
import com.aegisone.db.SQLiteReceiptChannel
import com.aegisone.db.SQLiteSystemEventChannel
import com.aegisone.zonea.FileBackedVersionFloorProvider
import com.aegisone.zonea.FileBackedZoneAStore
import com.aegisone.zonea.GrantRecord
import com.aegisone.zonea.ManifestRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import com.aegisone.db.SharedConnection
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end manifest evolution tests.
 *
 * Proves that authority changes across manifest upgrades and floor advancement
 * are enforced correctly, observable in the decision trail, and stable across
 * restart boundaries.
 *
 * No new production code — the infrastructure built across previous sessions
 * is sufficient to express these scenarios. The observability channels make
 * the transitions legible.
 *
 * MU full lifecycle (one test, sequential):
 *   MU-1: Boot on manifest v1, issue grant → GrantIssued(v1, floor=1)
 *   MU-2: Advance floor to 2, deny grant → GrantDenied(MANIFEST_VERSION_BELOW_FLOOR, v1, floor=2),
 *          broker transitions to RESTRICTED
 *   MU-3: Provision v2, reboot → BootVerified(v2), GrantIssued(v2, floor=2)
 *   MU-4: Restart (new BootOrchestrator, same files) → BootVerified(v2), GrantIssued(v2, floor=2)
 *   MU-5: authority_decisions trail reflects the full transition truthfully; 4 rows in order
 *
 * MU-BOOT-REJECTED: floor advancement also blocks TrustInit on old manifests.
 *   Proves the floor gate applies at boot time, not only at grant time.
 *
 * Note on provision(record, platformKey, versionFloor):
 *   provision() writes the versionFloor argument unconditionally. When provisioning
 *   a new manifest after a floor raise, pass the current raised floor — otherwise
 *   provision() would silently downgrade the floor. This is a Phase 0 limitation
 *   (production would provision the floor through a separate secure channel).
 */
class ManifestEvolutionE2ETest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var shared: SharedConnection

    private val PLATFORM_KEY = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    private val CAPABILITY   = "WRITE_NOTE"

    @BeforeEach
    fun setup() {
        shared = SQLiteBootstrap.openAndInitialize(File(tempDir, "receipts.db").absolutePath)
    }

    @AfterEach
    fun teardown() {
        runCatching { shared.close() }
    }

    private fun zoneADir() = File(tempDir, "zoneA")

    private fun makeRecord(version: Int) = ManifestRecord(
        version      = version,
        createdAt    = System.currentTimeMillis() - 1_000L,
        schemaValid  = true,
        signatureHex = ManifestRecord.bytesToHex(PLATFORM_KEY),
        grants = listOf(
            GrantRecord(CAPABILITY, AgentRole.EXECUTOR.name, GrantAuthority.SYSTEM_POLICY.name)
        ),
        agents = mapOf("exec-01" to AgentRole.EXECUTOR.name)
    )

    private fun boot(
        store: FileBackedZoneAStore,
        floor: FileBackedVersionFloorProvider,
        decisions: SQLiteAuthorityDecisionChannel,
        events: SQLiteSystemEventChannel
    ): BootResult = BootOrchestrator(
        zoneAStore               = store,
        versionFloorProvider     = floor,
        receiptChannel           = SQLiteReceiptChannel(shared),
        systemEventChannel       = events,
        authorityDecisionChannel = decisions
    ).boot()

    // --- MU full lifecycle ---

    @Test
    fun `MU full evolution — floor blocks old manifest, v2 reboot restores authority, trail proves transition`() {
        val store     = FileBackedZoneAStore(zoneADir())
        val floor     = FileBackedVersionFloorProvider(zoneADir())
        val decisions = SQLiteAuthorityDecisionChannel(shared)
        val events    = SQLiteSystemEventChannel(shared)

        // MU-1: boot on v1, issue grant — authority established under v1 at floor=1
        assertTrue(store.provision(makeRecord(1), PLATFORM_KEY, 1),
            "v1 provisioning must succeed")

        val boot1 = boot(store, floor, decisions, events)
        assertTrue(boot1 is BootResult.Active, "Boot on v1 must succeed")
        val broker1 = (boot1 as BootResult.Active).broker

        val grant1 = broker1.issueGrant(CAPABILITY, AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertNotNull(grant1, "Grant must be issued from v1 broker at floor=1")
        assertEquals(1, grant1.manifestVersion)

        // MU-2: advance floor — same broker re-reads floor at grant time and fails
        assertTrue(floor.raise(2), "Floor advancement to 2 must succeed")

        val grant2 = broker1.issueGrant(CAPABILITY, AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertNull(grant2, "Grant must be denied after floor advances past manifest v1")
        assertEquals(BrokerState.RESTRICTED, broker1.state,
            "Broker must be RESTRICTED after MANIFEST_VERSION_BELOW_FLOOR")

        // MU-3: provision v2 (floor stays at 2, not reset to 1), reboot, authority restored
        assertTrue(store.provision(makeRecord(2), PLATFORM_KEY, 2),
            "v2 provisioning must succeed (version 2 > 1, floor=2 preserved)")

        val boot2 = boot(store, floor, decisions, events)
        assertTrue(boot2 is BootResult.Active, "Boot on v2 with floor=2 must succeed")
        val broker2 = (boot2 as BootResult.Active).broker

        val grant3 = broker2.issueGrant(CAPABILITY, AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertNotNull(grant3, "Grant must be issued from v2 broker")
        assertEquals(2, grant3.manifestVersion)

        // MU-4: restart — new BootOrchestrator on same files, authority state is durable
        val boot3 = boot(store, floor, decisions, events)
        assertTrue(boot3 is BootResult.Active, "Second boot on v2 (restart) must succeed")

        val grant4 = (boot3 as BootResult.Active).broker
            .issueGrant(CAPABILITY, AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertNotNull(grant4, "Grant must be issued after restart — durable zone A state")
        assertEquals(2, grant4.manifestVersion)

        // MU-5: authority_decisions reflects the full transition in order
        data class DecRow(
            val type: String, val manifestVersion: Int?, val floorVersion: Int?, val reason: String?
        )
        val rows = shared.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT decision_type, manifest_version, floor_version, reason " +
                "FROM authority_decisions ORDER BY id"
            ).use { rs ->
                val list = mutableListOf<DecRow>()
                while (rs.next()) list.add(DecRow(
                    type            = rs.getString("decision_type"),
                    manifestVersion = rs.getObject("manifest_version")?.let { (it as Number).toInt() },
                    floorVersion    = rs.getObject("floor_version")?.let { (it as Number).toInt() },
                    reason          = rs.getString("reason")
                ))
                list
            }
        }

        assertEquals(4, rows.size, "Exactly 4 grant decisions must be recorded")

        // MU-1: grant issued under v1 at floor=1
        assertEquals("GrantIssued",  rows[0].type)
        assertEquals(1,              rows[0].manifestVersion)
        assertEquals(1,              rows[0].floorVersion)
        assertNull(rows[0].reason)

        // MU-2: denied because manifest v1 is below floor=2
        assertEquals("GrantDenied",                     rows[1].type)
        assertEquals("MANIFEST_VERSION_BELOW_FLOOR",    rows[1].reason)
        assertEquals(1,                                  rows[1].manifestVersion)
        assertEquals(2,                                  rows[1].floorVersion)

        // MU-3: granted under v2 at floor=2
        assertEquals("GrantIssued",  rows[2].type)
        assertEquals(2,              rows[2].manifestVersion)
        assertEquals(2,              rows[2].floorVersion)
        assertNull(rows[2].reason)

        // MU-4: granted under v2 after restart
        assertEquals("GrantIssued",  rows[3].type)
        assertEquals(2,              rows[3].manifestVersion)
        assertEquals(2,              rows[3].floorVersion)
        assertNull(rows[3].reason)

        // system_events: three BootVerified — v1, v2, v2
        val bootVersions = shared.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT manifest_version FROM system_events " +
                "WHERE event_type = 'BootVerified' ORDER BY id"
            ).use { rs ->
                val list = mutableListOf<Int>()
                while (rs.next()) list.add(rs.getInt("manifest_version"))
                list
            }
        }
        assertEquals(listOf(1, 2, 2), bootVersions,
            "BootVerified events must reflect the version sequence: v1 → v2 → v2 (restart)")
    }

    // --- MU-BOOT-REJECTED: floor advancement also gates TrustInit, not only grants ---

    @Test
    fun `MU-BOOT-REJECTED floor blocks TrustInit on old manifest — BootFailed recorded, v2 resolves it`() {
        val store     = FileBackedZoneAStore(zoneADir())
        val floor     = FileBackedVersionFloorProvider(zoneADir())
        val decisions = SQLiteAuthorityDecisionChannel(shared)
        val events    = SQLiteSystemEventChannel(shared)

        // Establish v1, confirm boot succeeds
        assertTrue(store.provision(makeRecord(1), PLATFORM_KEY, 1))
        assertTrue(boot(store, floor, decisions, events) is BootResult.Active,
            "Initial v1 boot must succeed")

        // Advance floor past v1
        assertTrue(floor.raise(2))

        // New boot attempt: TrustInit reads v1 manifest (version=1), floor=2 → VERSION_FLOOR failure
        val rejectedBoot = boot(store, floor, decisions, events)
        assertTrue(rejectedBoot is BootResult.Failed,
            "Boot must fail when manifest v1 (version=1) is below floor=2")
        assertTrue((rejectedBoot as BootResult.Failed).reason.contains("VERSION_FLOOR"),
            "Failure reason must identify VERSION_FLOOR")

        // BootFailed event recorded with correct step
        val failedStep = shared.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT step FROM system_events WHERE event_type = 'BootFailed'"
            ).use { rs -> if (rs.next()) rs.getString("step") else null }
        }
        assertEquals("VERSION_FLOOR", failedStep,
            "BootFailed event must carry VERSION_FLOOR as the failure step")

        // Provision v2 — resolves the floor constraint
        assertTrue(store.provision(makeRecord(2), PLATFORM_KEY, 2))
        val resolvedBoot = boot(store, floor, decisions, events)
        assertTrue(resolvedBoot is BootResult.Active,
            "Boot on v2 with floor=2 must succeed — floor constraint resolved")

        val grant = (resolvedBoot as BootResult.Active).broker
            .issueGrant(CAPABILITY, AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertNotNull(grant, "Grant must be issued after v2 boot")
        assertEquals(2, grant.manifestVersion,
            "Issued grant must carry the v2 manifest version")
    }
}
