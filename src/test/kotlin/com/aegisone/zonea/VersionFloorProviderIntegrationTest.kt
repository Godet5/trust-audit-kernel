package com.aegisone.zonea

import com.aegisone.broker.AgentRole
import com.aegisone.broker.GrantAuthority
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for FileBackedVersionFloorProvider.
 *
 * Verifies: correct reads, raise() monotonicity, shared file with
 * FileBackedZoneAStore, and the unprovisioned failure mode.
 */
class VersionFloorProviderIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    private fun storeDir() = File(tempDir, "zoneA")

    private fun baseRecord(version: Int = 1) = ManifestRecord(
        version = version,
        createdAt = System.currentTimeMillis(),
        schemaValid = true,
        signatureHex = ManifestRecord.bytesToHex(byteArrayOf(1, 2, 3, 4)),
        grants = listOf(GrantRecord("AGENT_SPAWN", AgentRole.PLANNER.name, GrantAuthority.SYSTEM_POLICY.name)),
        agents = mapOf("planner-01" to AgentRole.PLANNER.name)
    )

    // --- VF-1: reads floor written by ZoneAStore ---

    @Test
    fun `VF-1 currentFloor reads the floor provisioned by FileBackedZoneAStore`() {
        val dir = storeDir()
        val store = FileBackedZoneAStore(dir)
        assertTrue(store.provision(baseRecord(), byteArrayOf(1, 2, 3, 4), versionFloor = 3))

        val provider = FileBackedVersionFloorProvider(dir)
        assertEquals(3, provider.currentFloor(), "provider must read the floor written by provision()")
    }

    // --- VF-2: raise() to higher value ---

    @Test
    fun `VF-2 raise to strictly higher value succeeds and is immediately readable`() {
        val dir = storeDir()
        FileBackedZoneAStore(dir).provision(baseRecord(), byteArrayOf(1, 2, 3, 4), versionFloor = 1)

        val provider = FileBackedVersionFloorProvider(dir)
        assertTrue(provider.raise(5))
        assertEquals(5, provider.currentFloor(), "currentFloor must return the raised value immediately")
    }

    // --- VF-3: raise() rejects equal value ---

    @Test
    fun `VF-3 raise rejects equal value — floor is strictly increasing`() {
        val dir = storeDir()
        FileBackedZoneAStore(dir).provision(baseRecord(), byteArrayOf(1, 2, 3, 4), versionFloor = 4)

        val provider = FileBackedVersionFloorProvider(dir)
        assertFalse(provider.raise(4), "raise() with equal value must be rejected")
        assertEquals(4, provider.currentFloor(), "floor must be unchanged after rejected raise")
    }

    // --- VF-4: raise() rejects lower value ---

    @Test
    fun `VF-4 raise rejects lower value — floor cannot decrease`() {
        val dir = storeDir()
        FileBackedZoneAStore(dir).provision(baseRecord(), byteArrayOf(1, 2, 3, 4), versionFloor = 7)

        val provider = FileBackedVersionFloorProvider(dir)
        assertFalse(provider.raise(2), "raise() with lower value must be rejected")
        assertEquals(7, provider.currentFloor(), "floor must be unchanged after rejected raise")
    }

    // --- VF-5: multiple raises ---

    @Test
    fun `VF-5 floor can be raised multiple times in sequence`() {
        val dir = storeDir()
        FileBackedZoneAStore(dir).provision(baseRecord(), byteArrayOf(1, 2, 3, 4), versionFloor = 1)

        val provider = FileBackedVersionFloorProvider(dir)
        assertTrue(provider.raise(2))
        assertTrue(provider.raise(5))
        assertTrue(provider.raise(10))
        assertEquals(10, provider.currentFloor())

        // Now reject anything ≤ 10
        assertFalse(provider.raise(9))
        assertFalse(provider.raise(10))
        assertEquals(10, provider.currentFloor())
    }

    // --- VF-6: shared file — ZoneAStore provision updates what provider reads ---

    @Test
    fun `VF-6 provision on ZoneAStore with new floor is reflected by provider without restart`() {
        val dir = storeDir()
        val store = FileBackedZoneAStore(dir)
        val provider = FileBackedVersionFloorProvider(dir)

        store.provision(baseRecord(version = 1), byteArrayOf(1, 2, 3, 4), versionFloor = 2)
        assertEquals(2, provider.currentFloor())

        // Provision a new manifest version with a higher floor
        store.provision(baseRecord(version = 2), byteArrayOf(1, 2, 3, 4), versionFloor = 6)
        assertEquals(6, provider.currentFloor(),
            "provider must see the floor updated by a new ZoneAStore provision without needing to reinitialize")
    }

    // --- VF-7: unprovisioned storeDir throws ---

    @Test
    fun `VF-7 currentFloor throws IllegalStateException on unprovisioned Zone A`() {
        val provider = FileBackedVersionFloorProvider(storeDir())
        assertThrows<IllegalStateException> {
            provider.currentFloor()
        }
    }
}
