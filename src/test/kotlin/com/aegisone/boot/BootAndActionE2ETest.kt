package com.aegisone.boot

import com.aegisone.broker.AgentRole
import com.aegisone.broker.GrantAuthority
import com.aegisone.db.SQLiteAuditFailureChannel
import com.aegisone.db.SQLiteBootstrap
import com.aegisone.db.SQLiteReceiptChannel
import com.aegisone.execution.ActionExecutor
import com.aegisone.execution.ActionRequest
import com.aegisone.execution.CoordinatorResult
import com.aegisone.execution.ExecutionCoordinator
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end boot-and-action integration tests.
 *
 * Proves the first real path through the system:
 *   TrustInit (FileBackedZoneAStore) →
 *   CapabilityBroker (FileBackedVersionFloorProvider) →
 *   ExecutionCoordinator (SQLiteAuditFailureChannel + SQLiteReceiptChannel)
 *
 * All components are real except the ActionExecutor (a trivial lambda that
 * returns true). The executor is the correct boundary to keep fake here:
 * what it would do (e.g. write a note) is outside the trust-and-audit spine
 * being proven in Phase 0.
 *
 * E2E-1: Verified boot enables one governed action with durable receipt
 * E2E-2: Unprovisioned Zone A — boot fails, broker stays RESTRICTED
 */
class BootAndActionE2ETest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var conn: Connection

    private val PLATFORM_KEY  = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    private val VERSION_FLOOR = 1
    private val CAPABILITY    = "WRITE_NOTE"
    private val AGENT_ID      = "exec-01"
    private val SESSION_ID    = "sess-e2e"

    @BeforeEach
    fun setup() {
        conn = SQLiteBootstrap.openAndInitialize(File(tempDir, "receipts.db").absolutePath)
    }

    @AfterEach
    fun teardown() {
        runCatching { conn.close() }
    }

    private fun zoneADir() = File(tempDir, "zoneA")

    private fun provisionZoneA(store: FileBackedZoneAStore) {
        val record = ManifestRecord(
            version    = 1,
            createdAt  = System.currentTimeMillis() - 1_000L,  // not in the future
            schemaValid = true,
            signatureHex = ManifestRecord.bytesToHex(PLATFORM_KEY),  // Phase 0: sig == key
            grants = listOf(
                GrantRecord(CAPABILITY, AgentRole.EXECUTOR.name, GrantAuthority.SYSTEM_POLICY.name)
            ),
            agents = mapOf(AGENT_ID to AgentRole.EXECUTOR.name)
        )
        assertTrue(store.provision(record, PLATFORM_KEY, VERSION_FLOOR),
            "Provisioning test manifest must succeed")
    }

    // --- E2E-1: full chain — boot → authorize → execute → receipt durable ---

    @Test
    fun `E2E-1 verified boot enables one governed action with durable receipt`() {
        val store         = FileBackedZoneAStore(zoneADir())
        val floorProvider = FileBackedVersionFloorProvider(zoneADir())
        provisionZoneA(store)

        val receiptChannel = SQLiteReceiptChannel(conn)
        val auditChannel   = SQLiteAuditFailureChannel(conn)

        // Boot
        val bootResult = BootOrchestrator(store, floorProvider, receiptChannel).boot()
        assertTrue(bootResult is BootResult.Active,
            "Boot must succeed with a valid provisioned manifest")
        val broker = (bootResult as BootResult.Active).broker

        // Authorize — broker must be ACTIVE and must recognize the grant from the manifest
        val capability = broker.issueGrant(CAPABILITY, AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertNotNull(capability, "Broker must issue WRITE_NOTE grant after verified boot")
        assertEquals(CAPABILITY, capability.capabilityName)

        // Execute — trivial no-side-effect executor returns true (happy path)
        val coordinator = ExecutionCoordinator(
            auditFailureChannel = auditChannel,
            receiptChannel      = receiptChannel,
            executor            = object : ActionExecutor {
                override fun execute(request: ActionRequest) = true
            }
        )
        val result = coordinator.execute(ActionRequest(CAPABILITY, AGENT_ID, SESSION_ID))
        assertEquals(CoordinatorResult.SUCCESS, result,
            "Coordinator must return SUCCESS for a happy-path reversible action")

        // Verify: ActionReceipt is durably in the receipt DB
        val receiptCount = conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM receipts " +
                "WHERE receipt_type = 'ActionReceipt' " +
                "AND capability_name = '$CAPABILITY' " +
                "AND status = 'SUCCESS'"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(1, receiptCount,
            "ActionReceipt must be durably written to SQLiteReceiptChannel after SUCCESS")

        // Verify: PENDING record is durably in the audit failure DB
        val pendingCount = conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM audit_failure_records " +
                "WHERE record_type = 'Pending' " +
                "AND capability_name = '$CAPABILITY'"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(1, pendingCount,
            "PENDING record must be durably written to SQLiteAuditFailureChannel before execution")
    }

    // --- E2E-2: failed boot — unprovisioned Zone A ---

    @Test
    fun `E2E-2 unprovisioned Zone A — boot fails and reason identifies the step`() {
        val store         = FileBackedZoneAStore(zoneADir())  // no provision() call
        val floorProvider = FileBackedVersionFloorProvider(zoneADir())
        val receiptChannel = SQLiteReceiptChannel(conn)

        val bootResult = BootOrchestrator(store, floorProvider, receiptChannel).boot()
        assertTrue(bootResult is BootResult.Failed,
            "Boot must fail when Zone A has not been provisioned")
        assertTrue((bootResult as BootResult.Failed).reason.contains("ZONE_A_ACCESS"),
            "Failure reason must identify ZONE_A_ACCESS as the failure step")
    }
}
