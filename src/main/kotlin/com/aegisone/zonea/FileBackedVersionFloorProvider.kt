package com.aegisone.zonea

import com.aegisone.trust.VersionFloorProvider
import java.io.File
import java.io.FileOutputStream

/**
 * File-backed VersionFloorProvider for Phase 0.
 *
 * Reads the version floor from the same Zone A [storeDir] used by
 * FileBackedZoneAStore. Both classes share the "version_floor" file;
 * FileBackedZoneAStore.provision() writes it, and this class reads and
 * can raise it independently.
 *
 * No caching. The spec requires the Broker to re-read the floor at each
 * SYSTEM_POLICY grant issuance (implementationMap §2.2). Reading from disk
 * on every call is correct here — this is not a hot path.
 *
 * [currentFloor] throws IllegalStateException if the floor file is absent.
 * An absent floor means Zone A has not been provisioned. The Broker should
 * not reach grant issuance on an unprovisioned system (TrustInit would have
 * failed first), but failing loudly here is safer than returning a permissive
 * default.
 *
 * [raise] is not part of the VersionFloorProvider interface. It belongs on the
 * concrete class because raising the floor is a Zone A management operation,
 * not a read operation. Callers must hold the floor reference to raise it.
 *
 * Source: implementationMap-trust-and-audit-v1.md §2.2, §2.5
 */
class FileBackedVersionFloorProvider(storeDir: File) : VersionFloorProvider {

    private val floorFile = File(storeDir, "version_floor")

    /**
     * Returns the current version floor. Re-reads the file on every call.
     * @throws IllegalStateException if the floor file does not exist (unprovisioned Zone A).
     * @throws NumberFormatException if the file content is not a valid integer (corrupted state).
     */
    override fun currentFloor(): Int {
        check(floorFile.exists()) {
            "Version floor file absent at ${floorFile.absolutePath}. Zone A is not provisioned."
        }
        return floorFile.readText(Charsets.UTF_8).trim().toInt()
    }

    /**
     * Raise the version floor to [newFloor]. The new value must be strictly
     * greater than the current floor — the floor can only increase.
     *
     * Returns false (without modifying stored state) if:
     *   - [newFloor] <= current floor (monotonicity violation)
     *   - Any I/O error occurs during the fsynced write
     *
     * Returns true after the new floor is durably written.
     */
    fun raise(newFloor: Int): Boolean {
        val current = runCatching { currentFloor() }.getOrDefault(0)
        if (newFloor <= current) return false

        return try {
            FileOutputStream(floorFile).use { fos ->
                fos.write(newFloor.toString().toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
