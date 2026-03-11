package com.aegisone.boot

import com.aegisone.broker.AgentRole
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
import java.io.File
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Adversarial manifest corruption tests.
 *
 * Proves the manifest trust anchor fails closed under corruption, truncation,
 * and rollback attempts, and the boot lifecycle records the failure truthfully.
 *
 * MC-1: Truncated manifest JSON — MANIFEST_LOAD, BootFailed recorded.
 * MC-2: schemaValid = false in stored record — SCHEMA_VERIFY, BootFailed recorded.
 * MC-3: Signature bytes ≠ platform key — SIGNATURE_VERIFY, BootFailed recorded.
 * MC-4: Corrupted version_floor content (non-integer) — fails closed as BootFailed,
 *        not as an uncaught NumberFormatException. Requires the TrustInit catch block.
 * MC-5: platform_key.bin deleted post-provision — ZONE_A_ACCESS, BootFailed recorded.
 * MC-6: Manifest with createdAt in the future — TIMESTAMP, BootFailed recorded.
 * MC-7: Anti-rollback — provision() rejects version downgrade, stored manifest preserved,
 *        subsequent boot succeeds on the original version.
 * MC-8: Recovery after corruption — corrupt manifest blocks provisioning (fail-closed);
 *        operator-initiated deletion + reprovision restores boot; system_events trail
 *        records BootFailed then BootVerified in order.
 *
 * Production code changes paired with this file:
 *   TrustInit.verifyManifest(): added catch(Exception) block alongside finally —
 *     converts unexpected Zone A read errors (corrupted version_floor, unreadable
 *     platform key) into BootFailed instead of propagated exceptions. The system
 *     must fail closed; callers must not receive an unhandled exception.
 */
class ManifestCorruptionE2ETest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var conn: Connection

    private val PLATFORM_KEY = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    private val CAPABILITY   = "WRITE_NOTE"

    @BeforeEach
    fun setup() {
        conn = SQLiteBootstrap.openAndInitialize(File(tempDir, "receipts.db").absolutePath)
    }

    @AfterEach
    fun teardown() {
        runCatching { conn.close() }
    }

    private fun zoneADir() = File(tempDir, "zoneA")

    private fun makeRecord(
        version: Int,
        createdAt: Long = System.currentTimeMillis() - 1_000L,
        schemaValid: Boolean = true,
        signatureHex: String = ManifestRecord.bytesToHex(PLATFORM_KEY)
    ) = ManifestRecord(
        version      = version,
        createdAt    = createdAt,
        schemaValid  = schemaValid,
        signatureHex = signatureHex,
        grants       = listOf(GrantRecord(CAPABILITY, AgentRole.EXECUTOR.name, GrantAuthority.SYSTEM_POLICY.name)),
        agents       = mapOf("exec-01" to AgentRole.EXECUTOR.name)
    )

    private fun boot(store: FileBackedZoneAStore, floor: FileBackedVersionFloorProvider): BootResult =
        BootOrchestrator(
            zoneAStore               = store,
            versionFloorProvider     = floor,
            receiptChannel           = SQLiteReceiptChannel(conn),
            systemEventChannel       = SQLiteSystemEventChannel(conn),
            authorityDecisionChannel = SQLiteAuthorityDecisionChannel(conn)
        ).boot()

    private fun lastBootFailedStep(): String? =
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT step FROM system_events WHERE event_type = 'BootFailed' ORDER BY id DESC LIMIT 1"
            ).use { rs -> if (rs.next()) rs.getString("step") else null }
        }

    // --- MC-1: truncated manifest JSON ---

    @Test
    fun `MC-1 truncated manifest JSON — MANIFEST_LOAD failure, BootFailed recorded`() {
        val store = FileBackedZoneAStore(zoneADir())
        val floor = FileBackedVersionFloorProvider(zoneADir())
        assertTrue(store.provision(makeRecord(1), PLATFORM_KEY, 1))

        // Overwrite manifest.json with partial JSON — Gson parse fails, readManifest() returns null
        File(zoneADir(), "manifest.json").writeText("{\"version\":1,\"createdAt\":")

        val result = boot(store, floor)
        assertTrue(result is BootResult.Failed, "Truncated manifest must produce BootResult.Failed")
        assertEquals("MANIFEST_LOAD", lastBootFailedStep(),
            "BootFailed must carry MANIFEST_LOAD step for JSON parse failure")
    }

    // --- MC-2: schema invalid flag ---

    @Test
    fun `MC-2 schema invalid flag — SCHEMA_VERIFY failure, BootFailed recorded`() {
        val store = FileBackedZoneAStore(zoneADir())
        val floor = FileBackedVersionFloorProvider(zoneADir())
        assertTrue(store.provision(makeRecord(1, schemaValid = false), PLATFORM_KEY, 1))

        val result = boot(store, floor)
        assertTrue(result is BootResult.Failed, "Schema-invalid manifest must produce BootResult.Failed")
        assertEquals("SCHEMA_VERIFY", lastBootFailedStep(),
            "BootFailed must carry SCHEMA_VERIFY step")
    }

    // --- MC-3: signature mismatch ---

    @Test
    fun `MC-3 signature mismatch — SIGNATURE_VERIFY failure, BootFailed recorded`() {
        val store = FileBackedZoneAStore(zoneADir())
        val floor = FileBackedVersionFloorProvider(zoneADir())
        val wrongSig = ManifestRecord.bytesToHex(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        assertTrue(store.provision(makeRecord(1, signatureHex = wrongSig), PLATFORM_KEY, 1))

        val result = boot(store, floor)
        assertTrue(result is BootResult.Failed, "Signature mismatch must produce BootResult.Failed")
        assertEquals("SIGNATURE_VERIFY", lastBootFailedStep(),
            "BootFailed must carry SIGNATURE_VERIFY step")
    }

    // --- MC-4: corrupted version_floor file ---

    @Test
    fun `MC-4 corrupted version_floor — fails closed as BootFailed not as uncaught exception`() {
        val store = FileBackedZoneAStore(zoneADir())
        val floor = FileBackedVersionFloorProvider(zoneADir())
        assertTrue(store.provision(makeRecord(1), PLATFORM_KEY, 1))

        // Overwrite version_floor with non-integer garbage
        File(zoneADir(), "version_floor").writeText("NOT_A_NUMBER")

        // Without the TrustInit catch block, this throws NumberFormatException.
        // With the catch block, it returns BootResult.Failed and records BootFailed.
        val result = boot(store, floor)
        assertTrue(result is BootResult.Failed,
            "Corrupted version_floor must produce BootResult.Failed, not propagate NumberFormatException")
        assertNotNull(lastBootFailedStep(),
            "BootFailed event must be recorded — the failure is observable, not silent")
    }

    // --- MC-5: missing platform_key.bin ---

    @Test
    fun `MC-5 platform_key deleted post-provision — ZONE_A_ACCESS failure, BootFailed recorded`() {
        val store = FileBackedZoneAStore(zoneADir())
        val floor = FileBackedVersionFloorProvider(zoneADir())
        assertTrue(store.provision(makeRecord(1), PLATFORM_KEY, 1))

        // Delete the platform key — acquireAccess() checks existence of all required files
        assertTrue(File(zoneADir(), "platform_key.bin").delete(),
            "platform_key.bin must exist before deletion in this test")

        val result = boot(store, floor)
        assertTrue(result is BootResult.Failed, "Missing platform key must produce BootResult.Failed")
        assertEquals("ZONE_A_ACCESS", lastBootFailedStep(),
            "BootFailed must carry ZONE_A_ACCESS step — acquireAccess() returns null on missing sidecar")
    }

    // --- MC-6: future timestamp ---

    @Test
    fun `MC-6 future timestamp on manifest — TIMESTAMP failure, BootFailed recorded`() {
        val store = FileBackedZoneAStore(zoneADir())
        val floor = FileBackedVersionFloorProvider(zoneADir())
        assertTrue(store.provision(makeRecord(1, createdAt = System.currentTimeMillis() + 1_000_000L), PLATFORM_KEY, 1))

        val result = boot(store, floor)
        assertTrue(result is BootResult.Failed, "Future-timestamped manifest must produce BootResult.Failed")
        assertEquals("TIMESTAMP", lastBootFailedStep(),
            "BootFailed must carry TIMESTAMP step")
    }

    // --- MC-7: anti-rollback enforcement ---

    @Test
    fun `MC-7 anti-rollback — provision() rejects version downgrade, stored manifest preserved`() {
        val store = FileBackedZoneAStore(zoneADir())
        val floor = FileBackedVersionFloorProvider(zoneADir())
        assertTrue(store.provision(makeRecord(2), PLATFORM_KEY, 1), "v2 provisioning must succeed")

        // Attempt to roll back to v1
        assertFalse(store.provision(makeRecord(1), PLATFORM_KEY, 1),
            "provision() must reject version downgrade (v1 <= v2)")

        // v2 must be intact — boot succeeds, grant carries v2 version
        val result = boot(store, floor)
        assertTrue(result is BootResult.Active, "Boot must succeed on v2 after rejected rollback")
        val grant = (result as BootResult.Active).broker
            .issueGrant(CAPABILITY, AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertNotNull(grant, "Grant must be issued — v2 manifest is intact")
        assertEquals(2, grant!!.manifestVersion, "Issued grant must carry v2 manifest version")
    }

    // --- MC-8: recovery after corruption ---

    @Test
    fun `MC-8 recovery after corruption — corrupt file blocks reprovisioning, deletion + reprovision restores boot`() {
        val store = FileBackedZoneAStore(zoneADir())
        val floor = FileBackedVersionFloorProvider(zoneADir())

        // Establish v1, then corrupt the manifest
        assertTrue(store.provision(makeRecord(1), PLATFORM_KEY, 1))
        val manifestFile = File(zoneADir(), "manifest.json")
        manifestFile.writeText("garbage-corruption")

        // Boot fails on the corrupt manifest
        val corruptBoot = boot(store, floor)
        assertTrue(corruptBoot is BootResult.Failed, "Boot must fail on corrupt manifest")
        assertEquals("MANIFEST_LOAD", lastBootFailedStep())

        // Provisioning over a corrupt manifest is rejected (fail-closed anti-rollback):
        // readRecord() returns null → provision() cannot verify version monotonicity → returns false.
        // This is the correct behavior: provisioning must not silently overwrite an unreadable manifest
        // because the stored version is unknown and a downgrade cannot be ruled out.
        assertFalse(store.provision(makeRecord(2), PLATFORM_KEY, 1),
            "provision() must reject when the stored manifest is unreadable — version cannot be verified")

        // Recovery: operator explicitly removes the corrupt file, then reprovisions.
        // This is an intentional action that clears the unknown-version state.
        assertTrue(manifestFile.delete(), "Operator must be able to delete the corrupt manifest file")
        assertTrue(store.provision(makeRecord(2), PLATFORM_KEY, 1),
            "Provisioning v2 must succeed once the corrupt file is cleared")

        val recoveredBoot = boot(store, floor)
        assertTrue(recoveredBoot is BootResult.Active, "Boot must succeed after v2 is provisioned over the cleared state")

        // system_events: BootFailed then BootVerified — the trail tells the full story
        val eventTypes = conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT event_type FROM system_events ORDER BY id"
            ).use { rs ->
                val list = mutableListOf<String>()
                while (rs.next()) list.add(rs.getString(1))
                list
            }
        }
        assertEquals(listOf("BootFailed", "BootVerified"), eventTypes,
            "system_events must record the corruption failure then the successful recovery boot")
    }
}
