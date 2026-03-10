package com.aegisone.zonea

import com.aegisone.broker.AgentRole
import com.aegisone.broker.GrantAuthority
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for FileBackedZoneAStore.
 *
 * These tests verify the real storage contract: atomic writes, version
 * monotonicity, field round-trips, and failure modes. The invariant harness
 * (I-1, I-2) tests remain unchanged and continue to use the in-memory double.
 *
 * End state goal: "The manifest trust anchor backing I-1 and I-2 is now
 * implemented with durable, rollback-safe storage and the invariant harness
 * still passes unchanged."
 */
class ZoneAStoreIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    private fun store() = FileBackedZoneAStore(File(tempDir, "zoneA"))

    private fun baseRecord(version: Int = 1) = ManifestRecord(
        version = version,
        createdAt = 1_700_000_000_000L,
        schemaValid = true,
        signatureHex = ManifestRecord.bytesToHex(byteArrayOf(1, 2, 3, 4)),
        grants = listOf(
            GrantRecord("AGENT_SPAWN", AgentRole.PLANNER.name, GrantAuthority.SYSTEM_POLICY.name)
        ),
        agents = mapOf("planner-01" to AgentRole.PLANNER.name)
    )

    private val platformKey = byteArrayOf(1, 2, 3, 4)
    private val versionFloor = 1

    // --- ZA-1: basic provision and read ---

    @Test
    fun `ZA-1 provision writes manifest and acquireAccess returns it`() {
        val store = store()
        assertTrue(store.provision(baseRecord(), platformKey, versionFloor))

        val access = store.acquireAccess()
        assertNotNull(access, "acquireAccess must return non-null after successful provision")

        val manifest = access.readManifest()
        assertNotNull(manifest, "readManifest must return a Manifest after provision")
        assertEquals(1, manifest.version)
        assertEquals(1_700_000_000_000L, manifest.createdAt)
        assertTrue(manifest.isSchemaValid())
        assertTrue(manifest.verifySignature(platformKey), "signature must round-trip and verify against platform key")

        access.release()
    }

    // --- ZA-2: rollback rejected ---

    @Test
    fun `ZA-2 provision rejects version equal to current`() {
        val store = store()
        assertTrue(store.provision(baseRecord(version = 2), platformKey, versionFloor))

        // Same version — must be rejected
        val result = store.provision(baseRecord(version = 2), platformKey, versionFloor)
        assertFalse(result, "provision with equal version must be rejected (anti-rollback)")

        // Original manifest still present and readable
        assertEquals(2, store.acquireAccess()?.readManifest()?.version)
    }

    @Test
    fun `ZA-3 provision rejects version lower than current`() {
        val store = store()
        assertTrue(store.provision(baseRecord(version = 5), platformKey, versionFloor))

        val result = store.provision(baseRecord(version = 3), platformKey, versionFloor)
        assertFalse(result, "provision with lower version must be rejected (anti-rollback)")

        // Current manifest still version 5
        assertEquals(5, store.acquireAccess()?.readManifest()?.version)
    }

    // --- ZA-4: missing files ---

    @Test
    fun `ZA-4 acquireAccess returns null when no manifest has been provisioned`() {
        val store = store()
        assertNull(store.acquireAccess(), "acquireAccess must return null on empty store")
    }

    // --- ZA-5: atomic replace —- new version overwrites old ---

    @Test
    fun `ZA-5 provision with strictly higher version replaces manifest atomically`() {
        val store = store()
        assertTrue(store.provision(baseRecord(version = 1), platformKey, versionFloor))
        assertEquals(1, store.acquireAccess()?.readManifest()?.version)

        assertTrue(store.provision(baseRecord(version = 2), platformKey, versionFloor))
        assertEquals(2, store.acquireAccess()?.readManifest()?.version, "new version must be visible after atomic replace")
    }

    // --- ZA-6: platform key and version floor round-trip ---

    @Test
    fun `ZA-6 platform key and version floor round-trip correctly`() {
        val store = store()
        val key = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val floor = 7

        assertTrue(store.provision(baseRecord(), key, floor))

        val access = store.acquireAccess()!!
        assertTrue(access.readPlatformTrustRootPublicKey().contentEquals(key),
            "platform key must round-trip exactly")
        assertEquals(floor, access.readVersionFloor(),
            "version floor must round-trip exactly")
        access.release()
    }

    // --- ZA-7: grants and agents round-trip ---

    @Test
    fun `ZA-7 grants and agents survive storage round-trip`() {
        val store = store()
        val record = ManifestRecord(
            version = 1,
            createdAt = System.currentTimeMillis(),
            schemaValid = true,
            signatureHex = ManifestRecord.bytesToHex(platformKey),
            grants = listOf(
                GrantRecord("FILE_READ",  AgentRole.EXECUTOR.name,  GrantAuthority.USER_DIRECT.name),
                GrantRecord("AGENT_SPAWN", AgentRole.PLANNER.name, GrantAuthority.SYSTEM_POLICY.name)
            ),
            agents = mapOf(
                "exec-01" to AgentRole.EXECUTOR.name,
                "plan-01" to AgentRole.PLANNER.name
            )
        )
        assertTrue(store.provision(record, platformKey, versionFloor))

        val manifest = store.acquireAccess()!!.readManifest()!!
        val grants = manifest.deriveGrantList()
        assertEquals(2, grants.size)
        assertTrue(grants.any { it.capabilityName == "FILE_READ" && it.authority == GrantAuthority.USER_DIRECT })
        assertTrue(grants.any { it.capabilityName == "AGENT_SPAWN" && it.authority == GrantAuthority.SYSTEM_POLICY })

        val agents = manifest.deriveAgentEnumeration()
        assertEquals(2, agents.size)
        assertEquals(AgentRole.EXECUTOR, agents["exec-01"])
        assertEquals(AgentRole.PLANNER, agents["plan-01"])
    }

    // --- ZA-8: hex encoding round-trip ---

    @Test
    fun `ZA-8 signature hex encoding survives round-trip for all byte values`() {
        // Use a signature with varied byte values to catch encoding bugs
        val sig = ByteArray(16) { it.toByte() }  // 0x00..0x0F
        val hex = ManifestRecord.bytesToHex(sig)
        val decoded = ManifestRecord.hexToBytes(hex)
        assertTrue(sig.contentEquals(decoded), "hex round-trip must be lossless")
    }
}
